package mindustrytool.servermanager.service;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.channels.AsynchronousCloseException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.modelmapper.ModelMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.Volume;

import arc.files.Fi;
import arc.files.ZipFi;
import arc.struct.StringMap;
import arc.util.Log;
import arc.util.serialization.Json;
import arc.util.serialization.Jval;
import arc.util.serialization.Jval.Jformat;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mindustry.core.Version;
import mindustry.io.MapIO;
import mindustry.mod.Mods.ModMeta;
import mindustrytool.servermanager.EnvConfig;
import mindustrytool.servermanager.config.Config;
import mindustrytool.servermanager.types.request.HostServerRequest;
import mindustrytool.servermanager.types.request.InitServerRequest;
import mindustrytool.servermanager.service.GatewayService.GatewayClient;
import mindustrytool.servermanager.types.data.Player;
import mindustrytool.servermanager.types.data.ServerContainerMetadata;
import mindustrytool.servermanager.types.request.HostFromSeverRequest;
import mindustrytool.servermanager.types.response.LiveStats;
import mindustrytool.servermanager.types.response.ManagerMapDto;
import mindustrytool.servermanager.types.response.ManagerModDto;
import mindustrytool.servermanager.types.response.MapDto;
import mindustrytool.servermanager.types.response.MindustryPlayerDto;
import mindustrytool.servermanager.types.response.ModDto;
import mindustrytool.servermanager.types.response.ModMetaDto;
import mindustrytool.servermanager.types.response.ServerWithStatsDto;
import mindustrytool.servermanager.types.response.ServerFileDto;
import mindustrytool.servermanager.types.response.StatsDto;
import mindustrytool.servermanager.utils.ApiError;
import mindustrytool.servermanager.utils.SSE;
import mindustrytool.servermanager.utils.Utils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;
import reactor.core.publisher.Sinks.Many;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import reactor.util.retry.Retry;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServerService {

    private final DockerClient dockerClient;
    private final EnvConfig envConfig;
    private final ModelMapper modelMapper;
    private final GatewayService gatewayService;
    private final DockerService dockerService;

    private enum ServerFlag {
        KILL,
        RESTART
    }

    private final ConcurrentHashMap<UUID, EnumSet<ServerFlag>> serverFlags = new ConcurrentHashMap<>();
    private final Map<UUID, Statistics[]> statsSnapshots = new ConcurrentHashMap<>();
    private final Json json = new Json();

    private final Map<UUID, Disposable> streamSubscriptions = new ConcurrentHashMap<>();
    private final Map<UUID, Sinks.Many<String>> consoleStreams = new ConcurrentHashMap<>();
    private final Map<UUID, ResultCallback.Adapter<Frame>> logsAdapter = new ConcurrentHashMap<>();
    private final Map<UUID, ResultCallback.Adapter<Statistics>> statsAdapter = new ConcurrentHashMap<>();

    private final Long MAX_FILE_SIZE = 5000000l;

    private record ContainerStats(
            float cpuUsage,
            float jvmRamUsage, // in MB
            float totalRam // in MB
    ) {
    }

    private final HashMap<UUID, ContainerStats> stats = new HashMap<>();

    @PostConstruct
    private void init() {
        dockerClient.eventsCmd()
                .withEventFilter("start")
                .exec(new ResultCallback.Adapter<>() {
                    @Override
                    public void onNext(Event event) {
                        String containerId = event.getId();
                        var containers = dockerClient.listContainersCmd().withIdFilter(List.of(containerId)).exec();

                        if (containers.size() != 1) {
                            return;
                        }

                        var container = containers.get(0);

                        readMetadataFromContainer(container)
                                .ifPresent(metadata -> attachToLogs(containerId, metadata.getInit().getId()));
                    }
                });
    }

    @PostConstruct
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    private void findAndAttachToLogs() {
        var containers = dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withLabelFilter(List.of(Config.serverLabelName))//
                .exec();

        for (var container : containers) {
            var optional = readMetadataFromContainer(container);

            if (optional.isPresent()) {
                var metadata = optional.orElseThrow();

                if (!logsAdapter.containsKey(metadata.getInit().getId())) {
                    attachToLogs(container.getId(), metadata.getInit().getId());
                }

                if (!statsAdapter.containsKey(metadata.getInit().getId())) {
                    attachStats(metadata.getInit().getId());
                }
            }
        }
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    private void cron() {
        var containers = dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withLabelFilter(List.of(Config.serverLabelName))//
                .exec();

        Flux.fromIterable(containers)//
                .flatMap(container -> {
                    var optional = readMetadataFromContainer(container);

                    if (optional.isEmpty()) {
                        if (container.getState().equalsIgnoreCase("running")) {
                            dockerClient.stopContainerCmd(container.getId()).exec();
                        }
                        dockerClient.removeContainerCmd(container.getId()).exec();
                        log.error("Container " + container.getId() + " has no metadata");
                        return Mono.empty();
                    }

                    var metadata = optional.orElseThrow();
                    var server = metadata.getInit();

                    var isRunning = container.getState().equalsIgnoreCase("running");

                    var self = dockerService.getSelf();
                    var serverImage = dockerClient.inspectImageCmd(server.getImage()).exec();

                    var isSameServerHash = metadata.getServerImageHash().equals(serverImage.getId());
                    var isSameManagerHash = metadata.getServerManagerImageHash().equals(self.getId());

                    if (isRunning) {
                        if (metadata.getInit().isAutoTurnOff() == false) {
                            return stats(server.getId())//
                                    .flatMap(stats -> {
                                        if (!stats.isHosting()) {
                                            var flag = serverFlags.get(server.getId());

                                            if (flag == null) {
                                                serverFlags
                                                        .computeIfAbsent(server.getId(),
                                                                (_ignore) -> EnumSet.noneOf(ServerFlag.class))
                                                        .add(ServerFlag.RESTART);
                                            } else if (flag.contains(ServerFlag.RESTART)) {
                                                sendConsole(server.getId(),
                                                        "Restart server [%s] due to running but not hosting".formatted(
                                                                server.getId()));

                                                return restart(server.getId())
                                                        .thenReturn(gatewayService.of(server.getId()).getBackend())
                                                        .flatMap(backend -> backend.host(server.getId().toString()))
                                                        .then(syncStats(server.getId()));
                                            }
                                        } else {
                                            serverFlags.computeIfAbsent(server.getId(),
                                                    (_ignore) -> EnumSet.noneOf(ServerFlag.class))
                                                    .remove(ServerFlag.RESTART);
                                        }

                                        return Mono.empty();
                                    });
                        }

                        return gatewayService.of(server.getId())//
                                .getServer()//
                                .getPlayers()//
                                .collectList()//
                                .onErrorReturn((List.of()))
                                .flatMap(players -> {
                                    boolean shouldKill = players.isEmpty();

                                    var flag = serverFlags.computeIfAbsent(server.getId(),
                                            (_ignore) -> EnumSet.noneOf(ServerFlag.class));

                                    if (shouldKill) {
                                        if (flag != null && flag.contains(ServerFlag.KILL)) {
                                            sendConsole(server.getId(),
                                                    "Auto shut down server: %s".formatted(server.getId()));
                                            return remove(server.getId());
                                        } else {
                                            log.info("Server {} has no players, flag to kill.", server.getId());
                                            serverFlags.computeIfAbsent(server.getId(),
                                                    (_ignore) -> EnumSet.noneOf(ServerFlag.class))
                                                    .add(ServerFlag.KILL);
                                            sendConsole(server.getId(),
                                                    "Server [%s] has no players, flag to kill"
                                                            .formatted(server.getId()));

                                            return Mono.empty();
                                        }
                                    } else {
                                        if (flag != null && flag.contains(ServerFlag.KILL)) {
                                            serverFlags.computeIfAbsent(server.getId(),
                                                    (_ignore) -> EnumSet.noneOf(ServerFlag.class))
                                                    .remove(ServerFlag.KILL);
                                            log.info("Remove flag from server {}", server.getId());
                                            sendConsole(server.getId(),
                                                    "Remove kill flag from server: %s".formatted(server.getId()));
                                            return Mono.empty();
                                        }
                                    }

                                    return Mono.empty();
                                })//
                                .retry(5)//
                                .doOnError(error -> sendConsole(server.getId(), "Error: " + error.getMessage()))
                                .onErrorComplete();
                    } else {
                        if (!isSameManagerHash || !isSameServerHash) {
                            if (container.getState().equalsIgnoreCase("running")) {
                                dockerClient.stopContainerCmd(container.getId()).exec();
                            }
                            dockerClient.removeContainerCmd(container.getId()).exec();

                            sendConsole(server.getId(),
                                    "Remove server [%s] due to mismatch version hash".formatted(server.getId()));
                        }
                        return Mono.empty();
                    }
                })//
                .blockLast();
    }

    public Mono<Long> getTotalPlayers() {
        var containers = dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withLabelFilter(List.of(Config.serverLabelName))//
                .exec();

        return Flux.fromIterable(containers)//
                .map(container -> readMetadataFromContainer(container).orElseThrow())
                .flatMap(server -> stats(server.getInit().getId()))//
                .map(stats -> stats.getPlayers())//
                .collectList()//
                .flatMap(list -> Mono.justOrEmpty(list.stream().reduce((prev, curr) -> prev + curr)))
                .onErrorReturn(0L);//
    }

    public Container findContainerByServerId(UUID serverId) {
        var containers = dockerClient.listContainersCmd()//
                .withLabelFilter(Map.of(Config.serverIdLabel, serverId.toString()))//
                .withShowAll(true)//
                .exec();

        if (containers.isEmpty()) {
            return null;
        } else if (containers.size() == 1) {
            return containers.get(0);
        }
        log.info("Found " + containers.size() + " containers with id " + serverId + " delete duplicates");

        for (int i = 1; i < containers.size(); i++) {
            dockerClient.removeContainerCmd(containers.get(i).getId()).exec();
        }

        return containers.get(0);
    }

    public Mono<Void> shutdown(UUID serverId) {
        var container = findContainerByServerId(serverId);

        log.info("Found %s container to stop".formatted(container.getId()));

        dockerClient.stopContainerCmd(container.getId()).exec();
        log.info("Stopped: " + container.getNames()[0]);

        return syncStats(serverId);
    }

    private Mono<Void> syncStats(UUID serverId) {
        return stats(serverId).flatMap(stats -> gatewayService.of(serverId).getBackend().setStats(stats));
    }

    public Mono<Void> remove(UUID serverId) {
        var container = findContainerByServerId(serverId);

        if (container == null) {
            log.info("Container not found: " + serverId);
            return Mono.empty();
        }

        if (container.getState().equalsIgnoreCase("running")) {
            dockerClient.stopContainerCmd(container.getId()).exec();
            log.info("Stopped: " + container.getNames()[0]);
        }

        dockerClient.removeContainerCmd(container.getId()).exec();
        log.info("Removed: " + container.getNames()[0]);

        return Mono.empty();
    }

    public Mono<Void> restart(UUID serverId) {
        var container = findContainerByServerId(serverId);

        if (container == null) {
            log.info("Container not found: " + serverId);
            return Mono.empty();
        }

        if (container.getState().equalsIgnoreCase("running")) {
            dockerClient.stopContainerCmd(container.getId()).exec();
            log.info("Stopped: " + container.getNames()[0]);
        }

        dockerClient.startContainerCmd(container.getId()).exec();

        return syncStats(serverId);
    }

    public Mono<Boolean> pause(UUID serverId) {
        return gatewayService.of(serverId)//
                .getServer()//
                .pause()//
                .flatMap(res -> syncStats(serverId).thenReturn(res));
    }

    public Flux<ServerWithStatsDto> getServers() {
        var containers = dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withLabelFilter(List.of(Config.serverLabelName))//
                .exec();

        return Flux.fromIterable(containers)//
                .flatMap(container -> Mono.justOrEmpty(readMetadataFromContainer(container)))
                .map(server -> server.getInit())//
                .flatMap(server -> stats(server.getId())//
                        .map(stats -> modelMapper.map(server, ServerWithStatsDto.class)//
                                .setUsage(stats)
                                .setStatus(stats.getStatus()))//
                        .onErrorResume(error -> {
                            error.printStackTrace();
                            return Mono
                                    .just(modelMapper.map(server, ServerWithStatsDto.class)
                                            .setUsage(new StatsDto().setStatus("ERROR")));
                        })//
                );
    }

    public Mono<ServerWithStatsDto> getServer(UUID id) {
        var container = findContainerByServerId(id);

        if (container == null) {
            return Mono.just(new ServerWithStatsDto().setStatus("DELETED"));
        }

        var metadata = readMetadataFromContainer(container).orElseThrow();

        return stats(id)//
                .map(stats -> {
                    var dto = modelMapper.map(metadata.getInit(), ServerWithStatsDto.class);
                    return dto.setUsage(stats);
                });
    }

    public Flux<Player> getPlayers(UUID id) {
        return gatewayService.of(id).getServer()//
                .getPlayers()//
                .doOnError(error -> log.error("Failed to get players", error))//
                .onErrorResume(_ignore -> Flux.empty());
    }

    private Optional<ServerContainerMetadata> readMetadataFromContainer(Container container) {
        try {
            var label = container.getLabels().get(Config.serverLabelName);

            if (label == null) {
                return Optional.empty();
            }

            var metadata = Utils.readJsonAsClass(label, ServerContainerMetadata.class);

            if (metadata.getInit() == null || metadata.getHost() == null) {
                return Optional.empty();
            }

            return Optional.of(metadata);
        } catch (Exception _e) {
            return Optional.empty();
        }
    }

    public Flux<String> hostFromServer(HostFromSeverRequest request) {
        return SSE.create(callback -> {

            log.info("Init server: " + request.getInit().getId());

            if (request.getInit().getPort() <= 0) {
                throw new ApiError(HttpStatus.BAD_GATEWAY, "Invalid port number");
            }

            var containerOnRequestPort = dockerClient.listContainersCmd()//
                    .withShowAll(true)//
                    .withLabelFilter(List.of(Config.serverLabelName))//
                    .exec();

            for (var container : containerOnRequestPort) {

                for (var port : container.getPorts()) {

                    var optional = readMetadataFromContainer(container);

                    if (optional.isEmpty()) {
                        callback.accept("Container " + container.getId() + " has no metadata");
                        dockerClient.removeConfigCmd(container.getId()).exec();
                        continue;
                    }

                    var metadata = optional.get();

                    var hasSamePort = port.getPublicPort() == request.getInit().getPort();
                    var hasSameId = request.getInit().getId().equals(metadata.getInit().getId());

                    if (hasSamePort && !hasSameId) {
                        callback.accept("Port " + request.getInit().getPort() + " is already used by container: "
                                + container.getId()
                                + " attempt to delete it");

                        if (container.getState().equalsIgnoreCase("running")) {
                            dockerClient.stopContainerCmd(container.getId()).exec();
                        }
                        dockerClient.removeContainerCmd(container.getId()).exec();
                        break;
                    }
                }
            }

            String containerId = null;

            var containers = dockerClient.listContainersCmd()//
                    .withShowAll(true)//
                    .withLabelFilter(Map.of(Config.serverIdLabel, request.getInit().getId().toString()))//
                    .exec();

            if (containers.isEmpty()) {
                callback.accept("Container " + request.getInit().getId() + " got deleted, creating new");
                containerId = createNewServerContainer(request, callback);
            } else {
                var container = containers.get(0);
                containerId = container.getId();

                var optional = readMetadataFromContainer(container);

                if (optional.isEmpty()) {
                    callback.accept("Container " + container.getId() + " has no metadata");
                    if (container.getState().equalsIgnoreCase("running")) {
                        dockerClient.stopContainerCmd(container.getId()).exec();
                    }
                    dockerClient.removeContainerCmd(container.getId()).exec();
                    containerId = createNewServerContainer(request, callback);
                }

                callback.accept("Found container " + container.getNames()[0] + " status: " + container.getState());

                if (!container.getState().equalsIgnoreCase("running")) {
                    callback.accept("Start container " + container.getNames()[0]);
                    dockerClient.startContainerCmd(containerId).exec();
                }
            }

            var serverGateway = gatewayService.of(request.getInit().getId()).getServer();

            attachToLogs(containerId, request.getInit().getId());

            callback.accept("Waiting for server to start");

            return serverGateway//
                    .ok()
                    .then(Mono.fromRunnable(() -> callback.accept("Server started, waiting for hosting")))
                    .thenMany(host(request.getInit().getId(), request.getHost()));
        });
    }

    private String createNewServerContainer(HostFromSeverRequest request, Consumer<String> callback) {
        try {
            callback.accept("Pulling image: " + request.getInit().getImage());
            dockerClient.pullImageCmd(request.getInit().getImage())
                    .exec(new ResultCallback.Adapter<PullResponseItem>())
                    .awaitCompletion();

            callback.accept("Image pulled");
        } catch (InterruptedException e) {
            e.printStackTrace();
            callback.accept(e.getMessage());
            sendConsole(request.getInit().getId(), e.getMessage());
        }

        String serverId = request.getInit().getId().toString();
        String serverPath = Paths.get(Config.volumeFolderPath, "servers", serverId, "config").toAbsolutePath()
                .toString();

        Volume volume = new Volume("/config");
        Bind bind = new Bind(serverPath, volume);

        ExposedPort tcp = ExposedPort.tcp(Config.DEFAULT_MINDUSTRY_SERVER_PORT);
        ExposedPort udp = ExposedPort.udp(Config.DEFAULT_MINDUSTRY_SERVER_PORT);

        Ports portBindings = new Ports();

        portBindings.bind(tcp, Ports.Binding.bindPort(request.getInit().getPort()));
        portBindings.bind(udp, Ports.Binding.bindPort(request.getInit().getPort()));

        log.info("Create new container on port " + request.getInit().getPort());

        var image = request.getInit().getImage() == null || request.getInit().getImage().isEmpty()
                ? envConfig.docker().mindustryServerImage()
                : request.getInit().getImage();

        var self = dockerService.getSelf();
        var serverImage = dockerClient.inspectImageCmd(request.getInit().getImage()).exec();

        var currentMetadata = new ServerContainerMetadata()//
                .setServerImageHash(serverImage.getId())//
                .setServerManagerImageHash(self.getId())//
                .setHost(request.getHost())//
                .setInit(request.getInit());

        var command = dockerClient.createContainerCmd(image)//
                .withName(request.getInit().getId().toString())//
                .withLabels(Map.of(//
                        Config.serverLabelName, Utils.toJsonString(currentMetadata),
                        Config.serverIdLabel, request.getInit().getId().toString()//
                ));

        var env = new ArrayList<String>();
        var exposedPorts = new ArrayList<ExposedPort>();

        exposedPorts.add(tcp);
        exposedPorts.add(udp);

        env.addAll(request.getInit().getEnv().entrySet().stream().map(v -> v.getKey() + "=" + v.getValue()).toList());
        env.add("IS_HUB=" + request.getInit().isHub());
        env.add("SERVER_ID=" + serverId);

        if (Config.IS_DEVELOPMENT) {
            env.add("ENV=DEV");
            ExposedPort localTcp = ExposedPort.tcp(9999);
            portBindings.bind(localTcp, Ports.Binding.bindPort(9999));
            exposedPorts.add(localTcp);
        }

        command.withExposedPorts(exposedPorts)//
                .withEnv(env)
                // .withHealthcheck(
                // new HealthCheck()//
                // .withInterval(100000000000L)//
                // .withRetries(5)
                // .withTimeout(100000000000L) // 10 seconds
                // .withTest(List.of(
                // "CMD",
                // "sh",
                // "-c",
                // "wget --spider -q http://" + serverId.toString()
                // + ":9999/ok || exit 1")))
                .withHostConfig(HostConfig.newHostConfig()//
                        .withPortBindings(portBindings)//
                        .withNetworkMode("mindustry-server")//
                        // in bytes
                        .withMemory(request.getInit().getPlan().getRam() * 1024 * 1024)
                        .withCpuPeriod(100000l)
                        .withCpuQuota((long) ((request.getInit().getPlan().getCpu() * 100000)))
                        .withRestartPolicy(request.getInit().isAutoTurnOff()//
                                ? RestartPolicy.noRestart()
                                : RestartPolicy.onFailureRestart(5))
                        .withAutoRemove(request.getInit().isAutoTurnOff())
                        .withLogConfig(new LogConfig(LogConfig.LoggingType.JSON_FILE, Map.of(
                                "max-size", "100m",
                                "max-file", "5"//
                        )))
                        .withBinds(bind));

        var result = command.exec();

        var containerId = result.getId();

        dockerClient.startContainerCmd(containerId).exec();

        return containerId;
    }

    public Mono<List<String>> getMismatch(UUID serverId, InitServerRequest init) {
        var container = findContainerByServerId(serverId);

        if (container == null) {
            return Mono.empty();
        }

        return Mono.zipDelayError(stats(serverId), getMods(serverId).collectList()).map(zip -> {
            var stats = zip.getT1();
            var mods = zip.getT2();

            if (stats.getStatus().equals("NOT_RESPONSE")) {
                return List.of("Server not response");
            }

            List<String> result = new ArrayList<>();

            var meta = readMetadataFromContainer(container).orElseThrow();
            var serverImage = dockerClient.inspectImageCmd(meta.getInit().getImage()).exec();

            if (!meta.getServerImageHash().equals(serverImage.getId())) {
                result.add("Server image outdated, \ncurrent: " + serverImage.getId() + "\nexpected: "
                        + meta.getServerImageHash());
            }

            if (!meta.getInit().getImage().equals(init.getImage())) {
                result.add("Server image mismatch\ncurrent: " + meta.getInit().getImage() + "\nexpected: "
                        + init.getImage());
            }

            for (var mod : mods) {
                if (stats.getMods().stream()
                        .noneMatch(runningMod -> runningMod.getFilename().equals(mod.getFilename()))) {
                    result.add("Mod " + mod.getName() + ":" + mod.getFilename() + " is not loaded");
                }
            }

            for (var runningMod : stats.getMods()) {
                if (mods.stream()
                        .noneMatch(mod -> mod.getFilename().equals(runningMod.getFilename()))) {
                    result.add("Mod " + runningMod.getName() + ":" + runningMod.getFilename() + " is removed");
                }
            }

            if (init.isAutoTurnOff() != meta.getInit().isAutoTurnOff()) {
                result.add("Auto turn off mismatch\ncurrent: " + meta.getInit().isAutoTurnOff() + "\nexpected: "
                        + init.isAutoTurnOff());
            }

            if (!init.getMode().equals(meta.getInit().getMode())) {
                result.add("Mode mismatch\ncurrent: " + meta.getInit().getMode() + "\nexpected: " + init.getMode());
            }

            if (!init.getImage().equals(meta.getInit().getImage())) {
                result.add("Image outdated\ncurrent: " + meta.getInit().getImage() + "\nexpected: " + init.getImage());
            }

            for (var entry : init.getEnv().entrySet()) {
                if (!meta.getInit().getEnv().containsKey(entry.getKey())) {
                    result.add("Env " + entry.getKey() + " is not set");
                } else if (!meta.getInit().getEnv().get(entry.getKey()).equals(entry.getValue())) {
                    result.add("Env " + entry.getKey() + " mismatch\ncurrent: "
                            + meta.getInit().getEnv().get(entry.getKey()) + "\nexpected: " + entry.getValue());
                }
            }

            if (init.isHub() != meta.getInit().isHub()) {
                result.add("Hub mismatch\ncurrent: " + meta.getInit().isHub() + "\nexpected: " + init.isHub());
            }

            if (init.getPort() != meta.getInit().getPort()) {
                result.add("Port mismatch\ncurrent: " + meta.getInit().getPort() + "\nexpected: " + init.getPort());
            }

            if (!init.getHostCommand().equals(meta.getInit().getHostCommand())) {
                result.add("Host command mismatch\ncurrent: " + meta.getInit().getHostCommand() + "\nexpected: "
                        + init.getHostCommand());
            }

            if (!init.getPlan().getName().equals(meta.getInit().getPlan().getName())) {
                result.add("Plan mismatch\ncurrent: " + meta.getInit().getPlan().getName() + "\nexpected: "
                        + init.getPlan().getName());
            }

            if (init.getPlan().getCpu() != meta.getInit().getPlan().getCpu()) {
                result.add("Plan cpu mismatch\ncurrent: " + meta.getInit().getPlan().getCpu() + "\nexpected: "
                        + init.getPlan().getCpu());
            }

            if (init.getPlan().getRam() != meta.getInit().getPlan().getRam()) {
                result.add("Plan ram mismatch\ncurrent: " + meta.getInit().getPlan().getRam() + "\nexpected: "
                        + init.getPlan().getRam());
            }

            return result;
        });
    }

    public File getFile(UUID serverId, String path) {
        return Paths
                .get(Config.volumeFolderPath, "servers", serverId.toString(), "config",
                        URLDecoder.decode(path, StandardCharsets.UTF_8))
                .toFile();

    }

    private mindustry.maps.Map readMap(Fi file) {
        try {
            return MapIO.createMap(file, true);
        } catch (IOException e) {
            e.printStackTrace();
            return new mindustry.maps.Map(file, 0, 0, new StringMap(), true, 0, Version.build);
        }
    }

    public Flux<ManagerMapDto> getManagerMaps() {
        var folder = Paths.get(Config.volumeFolderPath, "servers").toFile();

        if (!folder.exists()) {
            return Flux.empty();
        }

        var result = new HashMap<String, Tuple2<Fi, List<UUID>>>();

        for (var serverFolder : new Fi(folder).list()) {
            var configFolder = serverFolder.child("config");

            if (!configFolder.exists()) {
                continue;
            }

            var mapFolder = configFolder.child("maps");

            if (!mapFolder.exists()) {
                continue;
            }

            for (var mapFile : mapFolder.list(file -> file.getName().endsWith(".msav"))) {
                result.computeIfAbsent(mapFile.name(), (_ignore) -> Tuples.of(mapFile, new ArrayList<>()))
                        .getT2()
                        .add(UUID.fromString(serverFolder.name()));
            }
        }
        return Flux.fromIterable(result.values()).map(entry -> {
            var map = readMap(entry.getT1());

            return new ManagerMapDto()//
                    .setName(map.name())//
                    .setFilename(map.file.name())
                    .setCustom(map.custom)
                    .setHeight(map.height)
                    .setServers(entry.getT2())
                    .setWidth(map.width);
        });
    }

    public Flux<ManagerModDto> getManagerMods() {
        var folder = Paths.get(Config.volumeFolderPath, "servers").toFile();

        if (!folder.exists()) {
            return Flux.empty();
        }

        var result = new HashMap<String, Tuple2<Fi, List<UUID>>>();

        for (var serverFolder : new Fi(folder).list()) {
            var configFolder = serverFolder.child("config");

            if (!configFolder.exists()) {
                continue;
            }

            var modFolder = configFolder.child("mods");

            if (!modFolder.exists()) {
                continue;
            }

            for (var mapFile : modFolder
                    .list(file -> file.getName().endsWith(".zip") || file.getName().endsWith(".jar"))) {
                result.computeIfAbsent(mapFile.name(), (_ignore) -> Tuples.of(mapFile, new ArrayList<>()))
                        .getT2()
                        .add(UUID.fromString(serverFolder.name()));
            }
        }

        return Flux.fromIterable(result.values()).flatMap(entry -> {
            try {
                var meta = loadMod(entry.getT1());

                return Mono.just(new ManagerModDto()//
                        .setFilename(entry.getT1().name())//
                        .setName(meta.name)
                        .setServers(entry.getT2())//
                        .setMeta(new ModMetaDto()//
                                .setAuthor(meta.author)//
                                .setDependencies(meta.dependencies.list())
                                .setDescription(meta.description)
                                .setDisplayName(meta.displayName)
                                .setHidden(meta.hidden)
                                .setInternalName(meta.internalName)
                                .setJava(meta.java)
                                .setMain(meta.main)
                                .setMinGameVersion(meta.minGameVersion)
                                .setName(meta.name)
                                .setRepo(meta.repo)
                                .setSubtitle(meta.subtitle)
                                .setVersion(meta.version)));
            } catch (Exception e) {
                e.printStackTrace();
                return Mono.empty();
            }
        });
    }

    public void deleteManagerMap(String filename) {
        var folder = Paths.get(Config.volumeFolderPath, "servers").toFile();

        if (!folder.exists()) {
            return;
        }

        for (var serverFolder : new Fi(folder).list()) {
            var configFolder = serverFolder.child("config");

            if (!configFolder.exists()) {
                continue;
            }

            var mapFolder = configFolder.child("maps");

            if (!mapFolder.exists()) {
                continue;
            }

            var mapFile = mapFolder.child(filename);

            if (mapFile.exists()) {
                mapFile.delete();
            }
        }
    }

    public void deleteManagerMod(String filename) {
        var folder = Paths.get(Config.volumeFolderPath, "servers").toFile();

        if (!folder.exists()) {
            return;
        }

        for (var serverFolder : new Fi(folder).list()) {
            var configFolder = serverFolder.child("config");

            if (!configFolder.exists()) {
                continue;
            }
            var modFolder = configFolder.child("mods");

            if (!modFolder.exists()) {
                continue;
            }

            var modFile = modFolder.child(filename);

            if (modFile.exists()) {
                modFile.delete();
            }
        }
    }

    public Flux<MapDto> getMaps(UUID serverId) {
        var folder = getFile(serverId, "maps");

        if (!folder.exists()) {
            return Flux.empty();
        }

        var maps = new Fi(folder).findAll()
                .map(file -> {
                    try {
                        return MapIO.createMap(file, true);
                    } catch (IOException e) {
                        e.printStackTrace();
                        return new mindustry.maps.Map(file, 0, 0, new StringMap(), true, 0, Version.build);
                    }
                }).map(map -> new MapDto()//
                        .setName(map.name())//
                        .setFilename(map.file.name())
                        .setCustom(map.custom)
                        .setHeight(map.height)
                        .setWidth(map.width))
                .list();

        return Flux.fromIterable(maps);
    }

    public Flux<ModDto> getMods(UUID serverId) {
        var folder = getFile(serverId, "mods");

        if (!folder.exists()) {
            return Flux.empty();
        }

        var modFiles = new Fi(folder).findAll(file -> file.extension().equalsIgnoreCase("jar") || file.extension()
                .equalsIgnoreCase("zip"));

        var result = new ArrayList<ModDto>();
        for (var modFile : modFiles) {
            try {
                var meta = loadMod(modFile);
                result.add(new ModDto()//
                        .setFilename(modFile.name())//
                        .setName(meta.name)
                        .setMeta(new ModMetaDto()//
                                .setAuthor(meta.author)//
                                .setDependencies(meta.dependencies.list())
                                .setDescription(meta.description)
                                .setDisplayName(meta.displayName)
                                .setHidden(meta.hidden)
                                .setInternalName(meta.internalName)
                                .setJava(meta.java)
                                .setMain(meta.main)
                                .setMinGameVersion(meta.minGameVersion)
                                .setName(meta.name)
                                .setRepo(meta.repo)
                                .setSubtitle(meta.subtitle)
                                .setVersion(meta.version)));
            } catch (ApiError error) {
                sendConsole(serverId,
                        "File doesn't have a '[mod/plugin].[h]json' file, delete and skipping: " + modFile.name());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return Flux.fromIterable(result);
    }

    private ModMeta findMeta(Fi file) {
        Fi metaFile = null;

        var metaFiles = List.of("mod.json", "mod.hjson", "plugin.json", "plugin.hjson");
        for (String name : metaFiles) {
            if ((metaFile = file.child(name)).exists()) {
                break;
            }
        }

        if (!metaFile.exists()) {
            return null;
        }

        ModMeta meta = json.fromJson(ModMeta.class, Jval.read(metaFile.readString()).toString(Jformat.plain));
        meta.cleanup();
        return meta;
    }

    private ModMeta loadMod(Fi sourceFile) throws Exception {
        ZipFi rootZip = null;

        try {
            Fi zip = sourceFile.isDirectory() ? sourceFile : (rootZip = new ZipFi(sourceFile));
            if (zip.list().length == 1 && zip.list()[0].isDirectory()) {
                zip = zip.list()[0];
            }

            ModMeta meta = findMeta(zip);

            if (meta == null) {
                log.warn("Mod @ doesn't have a '[mod/plugin].[h]json' file, delete and skipping.", zip);
                sourceFile.delete();
                throw new ApiError(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid file: No mod.json found.");
            }

            return meta;
        } catch (Exception e) {
            if (e instanceof ApiError) {
                throw e;
            }
            // delete root zip file so it can be closed on windows
            if (rootZip != null)
                rootZip.delete();
            throw new RuntimeException("Can not load mod from: " + sourceFile.name(), e);
        }
    }

    public Flux<ServerFileDto> getFiles(UUID serverId, String path) {
        var folder = getFile(serverId, path);

        return Mono.just(folder) //
                .filter(file -> file.length() < MAX_FILE_SIZE)//
                .switchIfEmpty(ApiError.badRequest("file-too-big"))//
                .flatMapMany(file -> {
                    try {
                        return file.isDirectory()//
                                ? Flux.fromArray(file.listFiles())//
                                        .map(child -> new ServerFileDto()//
                                                .name(child.getName())//
                                                .size(child.length())//
                                                .directory(child.isDirectory()))
                                : Flux.just(new ServerFileDto()//
                                        .name(file.getName())//
                                        .directory(file.isDirectory())//
                                        .size(file.length())//
                                        .data(Files.readString(file.toPath())));
                    } catch (IOException e) {
                        return Mono.error(e);
                    }
                });
    }

    public boolean fileExists(UUID serverId, String path) {
        var file = getFile(serverId, path);

        return file.exists();
    }

    public Mono<Void> createFile(UUID serverId, FilePart filePart, String path) {
        var folder = getFile(serverId, path);

        if (!folder.exists()) {
            folder.mkdirs();
        }

        if (folder.getPath().contains("mods")) {
            // Remove all old mods if exists
            var parts = filePart.filename().replace(".jar", "").split("_");
            if (parts.length == 2) {
                try {
                    var id = UUID.fromString(parts[0]);
                    new Fi(folder)//
                            .findAll()//
                            .select(f -> f.name().startsWith(id.toString()))//
                            .each(f -> {
                                sendConsole(serverId, "Delete old plugin/mod: " + f.name());
                                f.delete();
                            });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        File file = new File(folder, filePart.filename());

        try {
            if (!file.exists()) {
                file.createNewFile();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return filePart.transferTo(file);
    }

    public Mono<Void> deleteFile(UUID serverId, String path) {
        var file = getFile(serverId, path);

        if (!file.exists()) {
            log.info("Delete file: " + path + " is not exists");
            return Mono.empty();
        }

        if (file.isDirectory()) {
            var children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    child.delete();
                    log.info("Deleted: " + child.getPath());
                }
            }
        }
        log.info("Deleted: " + file.getPath());
        file.delete();

        return Mono.empty();
    }

    public Mono<Void> say(UUID serverId, String message) {
        var container = findContainerByServerId(serverId);

        if (container == null || !container.getState().equalsIgnoreCase("running")) {
            return Mono.empty();
        }

        return gatewayService.of(serverId).getServer().say(message);
    }

    public Flux<String> hostFromServer(UUID serverId, HostFromSeverRequest request) {
        return hostFromServer(request);
    }

    public Flux<String> host(UUID serverId, HostServerRequest request) {
        Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();

        var gateway = gatewayService.of(serverId);

        log.info("Host server: " + serverId);

        var hostMono = gateway.getServer().isHosting().flatMapMany(isHosting -> {
            if (isHosting) {
                Log.info("Server is hosting, do nothing");
                return Flux.just("Server is hosting, do nothing");
            }

            var container = findContainerByServerId(serverId);

            if (container == null) {
                Log.info("Server not initialized");
                return ApiError.badRequest("Server not initialized");
            }

            sink.tryEmitNext("Reading metadata");

            var server = readMetadataFromContainer(container).orElseThrow();

            String[] preHostCommand = { //
                    "config name %s".formatted(server.getInit().getName()), //
                    "config desc %s".formatted(server.getInit().getDescription()), //
                    "version"
            };

            for (var command : preHostCommand) {
                Log.info("Execute command: " + command);
                sink.tryEmitNext("Execute command: " + command);
            }

            return gateway.getServer()//
                    .sendCommand(preHostCommand)//
                    .then(gateway.getServer()
                            .host(new HostServerRequest()// \
                                    .setMode(request.getMode())
                                    .setHostCommand(request.getHostCommand())))//
                    .then(Mono.fromCallable(() -> sink.tryEmitNext("Wait for server status")))
                    .then(waitForHosting(gateway))
                    .then(syncStats(serverId))
                    .thenMany(Flux.just("Complete"));
        });

        return Flux.merge(sink.asFlux(), hostMono.doFinally(_ignore -> sink.tryEmitComplete()));
    }

    private Mono<Void> waitForHosting(GatewayClient gateway) {
        return gateway.getServer().isHosting()//
                .flatMap(isHosting -> isHosting //
                        ? Mono.empty()
                        : ApiError.badRequest("Server is not hosting yet"))//
                .retryWhen(Retry.fixedDelay(50, Duration.ofMillis(100)))
                .then();
    }

    public Mono<Void> ok(UUID serverId) {
        return gatewayService.of(serverId).getServer().ok();
    }

    public Flux<LiveStats> liveStats(UUID serverId) {
        return Flux.concat(
                stats(serverId).map(stats -> new LiveStats(0l, stats)),
                Flux.interval(Duration.ofSeconds(1))
                        .flatMap(index -> stats(serverId).map(stats -> new LiveStats(index + 1, stats))));
    }

    public Mono<StatsDto> stats(UUID serverId) {
        var container = findContainerByServerId(serverId);

        if (container == null) {
            return Mono.just(new StatsDto().setStatus("DELETED"));
        }

        if (!container.getState().equalsIgnoreCase("running")) {
            return Mono.just(new StatsDto().setStatus("DOWN"));
        }

        return gatewayService.of(serverId)//
                .getServer()//
                .getStats()//
                .onErrorResume(error -> {
                    Log.err(error.getMessage());
                    return Mono.empty();
                })
                .defaultIfEmpty(new StatsDto().setStatus("NOT_RESPONSE"))
                .map(serverStats -> {

                    var containerStats = stats.get(serverId);
                    if (containerStats != null) {
                        serverStats.setCpuUsage(containerStats.cpuUsage())//
                                .setTotalRam(containerStats.totalRam())//
                                .setJvmRamUsage(containerStats.jvmRamUsage());
                    }

                    return serverStats;
                });
    }

    public Mono<byte[]> getImage(UUID serverId) {
        return gatewayService.of(serverId)//
                .getServer()//
                .getImage();
    }

    public Mono<Void> setPlayer(UUID serverId, MindustryPlayerDto payload) {
        return gatewayService.of(serverId).getServer().setPlayer(payload);
    }

    public Mono<JsonNode> setConfig(UUID serverId, String key, String value) {
        var folderPath = Paths.get(Config.volumeFolderPath, "servers", serverId.toString(), "config", "config.json");
        File file = new File(folderPath.toUri());

        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                return Mono.error(e);
            }
        }

        if (file.isDirectory()) {
            deleteFileRecursive(file);

            try {
                file.createNewFile();
            } catch (IOException e) {
                return Mono.error(e);
            }
        }

        var config = Utils.readFile(file);
        var original = config;

        var keys = key.split("\\.");

        for (int i = 0; i < keys.length - 1; i++) {
            var k = keys[i];
            if (config.has(k)) {
                config = config.get(k);
            } else {
                config = ((ObjectNode) config).set(k, Utils.readString("{}"));
                config = config.get(k);
            }
        }
        ((ObjectNode) config).set(keys[keys.length - 1], Utils.readString(value));

        try {
            Files.writeString(file.toPath(), Utils.toJsonString(original));
        } catch (IOException e) {
            return Mono.error(e);
        }

        return Mono.just(original);
    }

    private void deleteFileRecursive(File file) {
        if (!file.exists())
            return;

        if (file.isDirectory()) {
            deleteFileRecursive(file);
        }

        file.delete();
    }

    public void sendConsole(UUID serverId, String message) {
        Sinks.Many<String> sink = consoleStreams.computeIfAbsent(serverId, id -> {
            Sinks.Many<String> newSink = Sinks.many().multicast().onBackpressureBuffer(10000);

            Disposable subscription = newSink.asFlux()
                    .bufferTimeout(100, Duration.ofMillis(500))
                    .flatMap(batch -> {
                        StringBuilder builder = new StringBuilder();

                        int counter = 0;
                        String previous = null;
                        for (var item : batch) {
                            if (previous != null && item.equals(previous)) {
                                counter++;
                            } else {
                                if (counter > 0) {
                                    builder.append(previous + "(" + counter + ")");
                                    counter = 0;
                                }
                                builder.append(item);
                            }
                            previous = item;
                        }

                        return gatewayService.of(serverId)//
                                .getBackend()
                                .sendConsole(String.join("", batch))
                                .onErrorResume(e -> {
                                    Log.info("[" + serverId + "]: " + String.join("", batch));
                                    e.printStackTrace();
                                    return Mono.empty();
                                });
                    }) // preserve order
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            null,
                            error -> log.error("Error in log stream for server {}", id, error),
                            () -> log.info("Log stream for server {} completed", id));

            streamSubscriptions.put(id, subscription);
            return newSink;
        });

        synchronized (sink) {
            var result = sink.tryEmitNext(message);

            if (result.isFailure()) {
                if (result == EmitResult.FAIL_CANCELLED) {
                    streamSubscriptions.remove(serverId);
                }
                System.out.println("[" + serverId + "] Log stream error: " + result);
            }
        }
    }

    private void attachStats(UUID id) {
        var container = findContainerByServerId(id);

        if (container == null) {
            return;
        }

        if (statsAdapter.containsKey(id)) {
            System.out.println("[" + id + "] Duplicate stats adapter");
            return;
        }

        var adapter = dockerClient.statsCmd(container.getId())
                .exec(new ResultCallback.Adapter<>() {
                    @Override
                    public void onNext(Statistics newStats) {

                        statsSnapshots.compute(id, (_ignore, prev) -> {
                            if (prev == null)
                                return new Statistics[] { null, newStats };
                            return new Statistics[] { prev[1], newStats };
                        });

                        var snapshots = statsSnapshots.get(id);
                        float cpuPercent = 0f;

                        if (snapshots != null && snapshots[0] != null && snapshots[1] != null) {
                            Long cpuDelta = snapshots[1].getCpuStats().getCpuUsage().getTotalUsage()
                                    - snapshots[0].getCpuStats().getCpuUsage().getTotalUsage();

                            Long systemDelta = Optional.ofNullable(snapshots[1].getCpuStats().getSystemCpuUsage())
                                    .orElse(0L)
                                    - Optional.ofNullable(snapshots[0].getCpuStats().getSystemCpuUsage()).orElse(0L);

                            Long cpuCores = snapshots[1].getCpuStats().getOnlineCpus();

                            if (systemDelta != null && systemDelta > 0 && cpuCores != null && cpuCores > 0) {
                                cpuPercent = ((float) cpuDelta / systemDelta * cpuCores * 100.0f);
                            }
                        }

                        long memUsage = Optional.ofNullable(newStats.getMemoryStats().getUsage()).orElse(0L); // bytes
                        long memLimit = Optional.ofNullable(newStats.getMemoryStats().getLimit()).orElse(0L); // bytes

                        float ramMB = memUsage / (1024f * 1024f);
                        float totalRamMB = memLimit / (1024f * 1024f);

                        stats.put(id, new ContainerStats(Math.max(cpuPercent, 0), Math.max(ramMB, 0),
                                Math.max(totalRamMB, 0)));
                    }

                    @Override
                    public void onComplete() {
                        System.out.println("[" + id + "] Stats stream ended.");
                        statsAdapter.remove(id);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        System.out.println("[" + id + "] Stats stream error: " + throwable.getMessage());
                        statsAdapter.remove(id);
                    }
                });

        statsAdapter.put(id, adapter);
    }

    private synchronized void attachToLogs(String containerId, UUID serverId) {
        if (logsAdapter.containsKey(serverId)) {
            System.out.println("[" + containerId + "] Duplicate logs adapter");
            return;
        }

        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                var message = new String(frame.getPayload());
                if (message.isBlank()) {
                    return;
                }

                sendConsole(serverId, message);
            }

            @Override
            public void onComplete() {
                System.out.println("[" + serverId + "] Log stream ended.");
                removeConsoleStream(serverId);
            }

            @Override
            public void onError(Throwable throwable) {
                if (!(throwable instanceof AsynchronousCloseException)) {
                    System.err
                            .println("[" + serverId + "] Log stream error: " + throwable.getMessage());
                    throwable.printStackTrace();
                }
                removeConsoleStream(serverId);
            }
        };

        logsAdapter.put(serverId, callback);

        dockerClient.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .withFollowStream(true)
                .withTail(0)
                .exec(callback);

        System.out.println("[" + serverId + "] Log stream attached.");
    }

    public void removeConsoleStream(UUID serverId) {
        consoleStreams.remove(serverId);
        Optional.ofNullable(streamSubscriptions.remove(serverId)).ifPresent(Disposable::dispose);
        Optional.ofNullable(logsAdapter.remove(serverId)).ifPresent(t -> {
            try {
                t.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
