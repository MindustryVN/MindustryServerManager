package mindustrytool.servermanager.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.modelmapper.ModelMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.InvocationBuilder.AsyncResultCallback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mindustrytool.servermanager.EnvConfig;
import mindustrytool.servermanager.config.Config;
import mindustrytool.servermanager.types.request.HostServerRequest;
import mindustrytool.servermanager.messages.request.SetPlayerMessageRequest;
import mindustrytool.servermanager.messages.request.StartServerMessageRequest;
import mindustrytool.servermanager.messages.response.StatsMessageResponse;
import mindustrytool.servermanager.service.GatewayService.GatewayClient;
import mindustrytool.servermanager.types.data.Player;
import mindustrytool.servermanager.types.data.ServerContainerMetadata;
import mindustrytool.servermanager.types.request.HostFromSeverRequest;
import mindustrytool.servermanager.types.response.ServerDto;
import mindustrytool.servermanager.types.response.ServerFileDto;
import mindustrytool.servermanager.utils.ApiError;
import mindustrytool.servermanager.utils.Utils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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

    private final ConcurrentHashMap<UUID, Boolean> serverKillFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Mono<Void>> hostingLocks = new ConcurrentHashMap<>();
    private final Map<UUID, Statistics[]> statsSnapshots = new ConcurrentHashMap<>();

    private final Long MAX_FILE_SIZE = 5000000l;

    private record ContainerStats(
            float cpuUsage,
            float ramUsage, // in MB
            float totalRam // in MB
    ) {
    }

    private final HashMap<UUID, ContainerStats> stats = new HashMap<>();

    @Scheduled(fixedDelay = 3, timeUnit = TimeUnit.SECONDS)
    private void updateStats() {
        var containers = dockerClient.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(List.of(Config.serverLabelName))
                .exec();

        for (var container : containers) {
            var optional = readMetadataFromContainer(container);

            if (optional.isEmpty()) {
                if (container.getState().equalsIgnoreCase("running")) {
                    dockerClient.stopContainerCmd(container.getId()).exec();
                }
                dockerClient.removeContainerCmd(container.getId()).exec();
                log.error("Container " + container.getId() + " has no metadata");
                continue;
            }

            var metadata = optional.orElseThrow();
            UUID id = metadata.getInit().getId();

            var newStats = dockerClient.statsCmd(container.getId())
                    .withNoStream(true)
                    .exec(new AsyncResultCallback<>())
                    .awaitResult();

            statsSnapshots.compute(id, (key, prev) -> {
                if (prev == null)
                    return new Statistics[] { null, newStats };
                return new Statistics[] { prev[1], newStats };
            });

            var snapshots = statsSnapshots.get(id);
            float cpuPercent = 0f;

            if (snapshots != null && snapshots[0] != null && snapshots[1] != null) {
                long cpuDelta = snapshots[1].getCpuStats().getCpuUsage().getTotalUsage()
                        - snapshots[0].getCpuStats().getCpuUsage().getTotalUsage();

                long systemDelta = snapshots[1].getCpuStats().getSystemCpuUsage()
                        - snapshots[0].getCpuStats().getSystemCpuUsage();

                long cpuCores = snapshots[1].getCpuStats().getOnlineCpus();

                if (systemDelta > 0 && cpuCores > 0) {
                    cpuPercent = (float) cpuDelta / systemDelta * cpuCores * 100.0f;
                }
            }

            long memUsage = newStats.getMemoryStats().getUsage(); // bytes
            long memLimit = newStats.getMemoryStats().getLimit(); // bytes

            float ramMB = memUsage / (1024f * 1024f);
            float totalRamMB = memLimit / (1024f * 1024f);

            stats.put(id, new ContainerStats(cpuPercent, ramMB, totalRamMB));
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
                    var backend = gatewayService.of(server.getId()).getBackend();

                    if (isRunning) {
                        return gatewayService.of(server.getId())//
                                .getServer()//
                                .getPlayers()//
                                .collectList()//
                                .onErrorReturn((List.of()))
                                .flatMap(players -> {
                                    boolean shouldKill = players.isEmpty() && server.isAutoTurnOff();

                                    var killFlag = serverKillFlags.getOrDefault(server.getId(), false);

                                    if (shouldKill) {
                                        if (killFlag) {
                                            return shutdown(server.getId())//
                                                    .then(backend.sendConsole(
                                                            "Auto shut down server: " + server.getId()));
                                        } else {
                                            log.info("Server {} has no players, flag to kill.", server.getId());
                                            serverKillFlags.put(server.getId(), true);
                                            return backend.sendConsole(
                                                    "Server " + server.getId() + " has no players, flag to kill");
                                        }
                                    } else {
                                        if (killFlag) {
                                            serverKillFlags.put(server.getId(), false);
                                            log.info("Remove flag from server {}", server.getId());
                                            return backend
                                                    .sendConsole("Remove kill flag from server  " + server.getId());
                                        }
                                    }

                                    return Mono.empty();
                                })//
                                .retry(5)//
                                .doOnError(error -> gatewayService.of(server.getId())//
                                        .getBackend()
                                        .sendConsole("Server not response, auto shutdown: " + server
                                                .getId()))
                                .onErrorComplete();
                    } else {
                        if (!isSameManagerHash || !isSameServerHash) {
                            if (container.getState().equalsIgnoreCase("running")) {
                                dockerClient.stopContainerCmd(container.getId()).exec();
                            }
                            dockerClient.removeContainerCmd(container.getId()).exec();

                            backend.sendConsole("Remove server " + server.getId() + " due to mismatch version hash")
                                    .subscribe();
                        }
                        return Mono.empty();
                    }
                })//
                .subscribe();
    }

    public Mono<Integer> getTotalPlayers() {
        var containers = dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withLabelFilter(List.of(Config.serverLabelName))//
                .exec();

        return Flux.fromIterable(containers)//
                .map(container -> readMetadataFromContainer(container).orElseThrow())
                .flatMap(server -> gatewayService.of(server.getInit().getId()).getBackend().getTotalPlayer())//
                .onErrorReturn(0)//
                .collectList()//
                .flatMap(list -> Mono.justOrEmpty(list.stream().reduce((prev, curr) -> prev + curr)));
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

        return Mono.empty();

    }

    public Mono<Void> remove(UUID serverId) {
        var container = findContainerByServerId(serverId);

        log.info("Found %s container to stop".formatted(container.getId()));

        if (container.getState().equalsIgnoreCase("running")) {
            dockerClient.stopContainerCmd(container.getId()).exec();
            log.info("Stopped: " + container.getNames()[0]);
        }

        dockerClient.removeContainerCmd(container.getId()).exec();
        log.info("Removed: " + container.getNames()[0]);
        return Mono.empty();
    }

    public Flux<ServerDto> getServers() {
        var containers = dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withLabelFilter(List.of(Config.serverLabelName))//
                .exec();

        return Flux.fromIterable(containers)//
                .flatMap(container -> Mono.justOrEmpty(readMetadataFromContainer(container)))
                .map(server -> server.getInit())//
                .flatMap(server -> stats(server.getId())//
                        .map(stats -> modelMapper.map(server, ServerDto.class).setUsage(stats).setStatus(stats.status))//
                        .onErrorResume(ignore -> Mono
                                .just(modelMapper.map(server, ServerDto.class).setUsage(new StatsMessageResponse())))//
                );
    }

    public Mono<ServerDto> getServer(UUID id) {
        var container = findContainerByServerId(id);

        if (container == null) {
            return Mono.just(new ServerDto().setStatus("DELETED"));
        }

        var containerStats = stats.get(id);
        var metadata = readMetadataFromContainer(container).orElseThrow();

        return stats(id)//
                .map(stats -> {
                    var dto = modelMapper.map(metadata.getInit(), ServerDto.class);
                    if (containerStats != null) {
                        stats.setCpuUsage(containerStats.cpuUsage())//
                                .setTotalRam(containerStats.totalRam())//
                                .setRamUsage(containerStats.ramUsage());
                    }
                    return dto.setUsage(stats);
                });
    }

    public Flux<Player> getPlayers(UUID id) {
        return gatewayService.of(id).getServer()//
                .getPlayers()//
                .doOnError(error -> log.error("Failed to get players", error))//
                .onErrorResume(error -> Flux.empty());
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

    public Mono<Void> initServer(HostFromSeverRequest request) {
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
                    log.error("Container " + container.getId() + " has no metadata");
                    dockerClient.removeConfigCmd(container.getId()).exec();
                    continue;
                }

                var metadata = optional.get();

                var hasSamePort = port.getPublicPort() == request.getInit().getPort();
                var hasSameId = request.getInit().getId().equals(metadata.getInit().getId());

                if (hasSamePort && !hasSameId) {
                    log.info("Port " + request.getInit().getPort() + " is already used by container: "
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

        String containerId;

        var containers = dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withLabelFilter(Map.of(Config.serverIdLabel, request.getInit().getId().toString()))//
                .exec();

        if (containers.isEmpty()) {
            log.warn("Container " + request.getInit().getId() + " got deleted, creating new");
            containerId = createNewServerContainer(request);
        } else {
            var container = containers.get(0);
            containerId = container.getId();

            var optional = readMetadataFromContainer(container);

            if (optional.isEmpty()) {
                log.error("Container " + container.getId() + " has no metadata");
                if (container.getState().equalsIgnoreCase("running")) {
                    dockerClient.stopContainerCmd(container.getId()).exec();
                }
                dockerClient.removeContainerCmd(container.getId()).exec();
                containerId = createNewServerContainer(request);
            }

            log.info("Found container " + container.getNames()[0] + " status: " + container.getState());

            if (!container.getState().equalsIgnoreCase("running")) {
                log.info("Start container " + container.getNames()[0]);
                dockerClient.startContainerCmd(containerId).exec();
            }
        }

        log.info("Created server: " + request.getInit().getName());

        var serverGateway = gatewayService.of(request.getInit().getId()).getServer();

        return serverGateway//
                .ok()
                .then(serverGateway.isHosting())//
                .flatMap(isHosting -> isHosting //
                        ? Mono.empty()
                        : host(request.getInit().getId(), request.getHost()));
    }

    private String createNewServerContainer(HostFromSeverRequest request) {
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
                        .withMemory(524288000l)
                        .withRestartPolicy(request.getInit().isAutoTurnOff()//
                                ? RestartPolicy.noRestart()
                                : RestartPolicy.onFailureRestart(5))
                        .withAutoRemove(request.getInit().isAutoTurnOff())
                        .withBinds(bind));

        var result = command.exec();

        var containerId = result.getId();

        dockerClient.startContainerCmd(containerId).exec();

        return containerId;
    }

    public File getFile(UUID serverId, String path) {
        return Paths.get(Config.volumeFolderPath, "servers", serverId.toString(), "config", path).toFile();

    }

    public Flux<ServerFileDto> getFiles(UUID serverId, String path) {
        var folder = Paths.get(Config.volumeFolderPath, "servers", serverId.toString(), "config", path).toFile();

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

    public Mono<Void> createFile(UUID serverId, FilePart filePart, String path) {
        var folderPath = Paths.get(Config.volumeFolderPath, "servers", serverId.toString(), "config", path);

        File folder = new File(folderPath.toUri());

        if (!folder.exists()) {
            folder.mkdirs();
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
        var file = new File(
                Paths.get(Config.volumeFolderPath, "servers", serverId.toString(), "config", path).toString());

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

    public Mono<Void> sendCommand(UUID serverId, String command) {
        return gatewayService.of(serverId).getServer().sendCommand(command);
    }

    public Mono<Void> hostFromServer(UUID serverId, HostFromSeverRequest request) {
        return initServer(request);
    }

    public Mono<Void> host(UUID serverId, HostServerRequest request) {
        var gateway = gatewayService.of(serverId);

        log.info("Host server: " + serverId);

        return hostingLocks.computeIfAbsent(serverId, id -> gateway.getServer().isHosting().flatMap(isHosting -> {
            if (isHosting) {
                return Mono.empty();
            }

            var container = findContainerByServerId(serverId);

            if (container == null) {
                return ApiError.badRequest("Server not initialized");
            }

            var server = readMetadataFromContainer(container).orElseThrow();

            String[] preHostCommand = { //
                    "config name %s".formatted(server.getInit().getName()), //
                    "config desc %s".formatted(server.getInit().getDescription())//
            };

            return gateway.getServer()//
                    .sendCommand(preHostCommand)//
                    .then(gateway.getServer()
                            .host(new StartServerMessageRequest()// \
                                    .setMode(request.getMode())
                                    .setCommands(request.getHostCommand())))//
                    .then(waitForHosting(gateway));
        })
                .doFinally((_) -> hostingLocks.remove(serverId)));
    }

    private Mono<Void> waitForHosting(GatewayClient gateway) {
        return gateway.getServer().isHosting()//
                .flatMap(isHosting -> isHosting //
                        ? Mono.empty()
                        : ApiError.badRequest("Server is not hosting yet"))//
                .retryWhen(Retry.fixedDelay(10, Duration.ofSeconds(1)))//
                .then();
    }

    public Mono<Void> ok(UUID serverId) {
        return gatewayService.of(serverId).getServer().ok();
    }

    public Mono<StatsMessageResponse> stats(UUID serverId) {
        return gatewayService.of(serverId)//
                .getServer()//
                .getStats()//
                .map(serverStats -> {
                    var container = findContainerByServerId(serverId);

                    if (container == null) {
                        return serverStats;
                    }
                    var containerStats = stats.get(serverId);
                    if (containerStats != null) {
                        serverStats.setCpuUsage(containerStats.cpuUsage())//
                                .setTotalRam(containerStats.totalRam())//
                                .setRamUsage(containerStats.ramUsage());
                    }

                    return serverStats;
                })
                .onErrorReturn(getStatIfError(serverId));
    }

    public Mono<StatsMessageResponse> detailStats(UUID serverId) {
        return gatewayService.of(serverId)//
                .getServer()//
                .getDetailStats()//
                .map(serverStats -> {
                    var container = findContainerByServerId(serverId);

                    if (container == null) {
                        return serverStats;
                    }
                    var containerStats = stats.get(serverId);

                    if (containerStats != null) {
                        serverStats.setCpuUsage(containerStats.cpuUsage())//
                                .setTotalRam(containerStats.totalRam())//
                                .setRamUsage(containerStats.ramUsage());
                    }

                    return serverStats;
                })
                .onErrorReturn(getStatIfError(serverId));

    }

    private StatsMessageResponse getStatIfError(UUID serverId) {
        var containers = dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withLabelFilter(Map.of(Config.serverIdLabel, serverId.toString()))//
                .exec();

        var status = containers.isEmpty() //
                ? "DELETED"
                : containers.get(0)//
                        .getState()//
                        .equalsIgnoreCase("running")//
                                ? "NOT_RESPONSE"
                                : "DOWN";

        var response = new StatsMessageResponse()
                .setRamUsage(0)
                .setTotalRam(0)
                .setPlayers(0)
                .setMapName("")
                .setMods(new ArrayList<>())
                .setStatus(status);

        return response;
    }

    public Mono<Void> setPlayer(UUID serverId, SetPlayerMessageRequest payload) {
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
}
