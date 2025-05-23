package mindustrytool.servermanager.service;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.InvocationBuilder.AsyncResultCallback;

import arc.files.Fi;
import arc.files.ZipFi;
import arc.struct.StringMap;
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
import mindustrytool.servermanager.service.GatewayService.GatewayClient;
import mindustrytool.servermanager.types.data.Player;
import mindustrytool.servermanager.types.data.ServerContainerMetadata;
import mindustrytool.servermanager.types.request.HostFromSeverRequest;
import mindustrytool.servermanager.types.response.MapDto;
import mindustrytool.servermanager.types.response.MindustryPlayerDto;
import mindustrytool.servermanager.types.response.ModDto;
import mindustrytool.servermanager.types.response.ModDto.ModMetaDto;
import mindustrytool.servermanager.types.response.ServerWithStatsDto;
import mindustrytool.servermanager.types.response.ServerFileDto;
import mindustrytool.servermanager.types.response.StatsDto;
import mindustrytool.servermanager.utils.ApiError;
import mindustrytool.servermanager.utils.Utils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
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
    private final Map<UUID, Statistics[]> statsSnapshots = new ConcurrentHashMap<>();
    private final Json json = new Json();

    private final Map<UUID, Disposable> streamSubscriptions = new ConcurrentHashMap<>();
    private final Map<UUID, Sinks.Many<String>> consoleStreams = new ConcurrentHashMap<>();

    private final Long MAX_FILE_SIZE = 5000000l;

    private record ContainerStats(
            float cpuUsage,
            float ramUsage, // in MB
            float totalRam // in MB
    ) {
    }

    private final HashMap<UUID, ContainerStats> stats = new HashMap<>();

    @PostConstruct
    private void init() {
        log.info("ServerService initialized");

        var containers = dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withLabelFilter(List.of(Config.serverLabelName))//
                .exec();

        for (var container : containers) {
            var optional = readMetadataFromContainer(container);

            if (optional.isPresent()) {
                var metadata = optional.orElseThrow();
                attachToLogs(container.getId(), metadata.getInit().getId());
            }
        }
    }

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

                Long systemDelta = Optional.ofNullable(snapshots[1].getCpuStats().getSystemCpuUsage()).orElse(0L)
                        - Optional.ofNullable(snapshots[0].getCpuStats().getSystemCpuUsage()).orElse(0L);

                Long cpuCores = snapshots[1].getCpuStats().getOnlineCpus();

                if (systemDelta != null && systemDelta > 0 && cpuCores != null && cpuCores > 0) {
                    cpuPercent = (float) cpuDelta / systemDelta * cpuCores * 100.0f;
                }
            }

            long memUsage = Optional.ofNullable(newStats.getMemoryStats().getUsage()).orElse(0L); // bytes
            long memLimit = Optional.ofNullable(newStats.getMemoryStats().getLimit()).orElse(0L); // bytes

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
                                            sendConsole(server.getId(), "Auto shut down server: " + server.getId());
                                            return shutdown(server.getId());
                                        } else {
                                            log.info("Server {} has no players, flag to kill.", server.getId());
                                            serverKillFlags.put(server.getId(), true);
                                            sendConsole(server.getId(),
                                                    "Server " + server.getId() + " has no players, flag to kill");

                                            return Mono.empty();
                                        }
                                    } else {
                                        if (killFlag) {
                                            serverKillFlags.put(server.getId(), false);
                                            log.info("Remove flag from server {}", server.getId());
                                            sendConsole(server.getId(),
                                                    "Remove kill flag from server  " + server.getId());
                                            return Mono.empty();
                                        }
                                    }

                                    return Mono.empty();
                                })//
                                .retry(5)//
                                .doOnError(_ignore -> sendConsole(server.getId(),
                                        "Server not response, auto shutdown: " + server.getId()))
                                .onErrorComplete();
                    } else {
                        if (!isSameManagerHash || !isSameServerHash) {
                            if (container.getState().equalsIgnoreCase("running")) {
                                dockerClient.stopContainerCmd(container.getId()).exec();
                            }
                            dockerClient.removeContainerCmd(container.getId()).exec();

                            sendConsole(server.getId(),
                                    "Remove server " + server.getId() + " due to mismatch version hash");
                        }
                        return Mono.empty();
                    }
                })//
                .subscribe();
    }

    public Mono<Long> getTotalPlayers() {
        var containers = dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withLabelFilter(List.of(Config.serverLabelName))//
                .exec();

        return Flux.fromIterable(containers)//
                .map(container -> readMetadataFromContainer(container).orElseThrow())
                .flatMap(server -> gatewayService.of(server.getInit().getId()).getServer().getStats())//
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

        log.info("Found %s container to stop".formatted(container.getId()));

        if (container.getState().equalsIgnoreCase("running")) {
            dockerClient.stopContainerCmd(container.getId()).exec();
            log.info("Stopped: " + container.getNames()[0]);
        }

        dockerClient.removeContainerCmd(container.getId()).exec();
        log.info("Removed: " + container.getNames()[0]);

        return syncStats(serverId);
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
                        .map(stats -> modelMapper.map(server, ServerWithStatsDto.class).setUsage(stats)
                                .setStatus(stats.status))//
                        .onErrorResume(error -> {
                            error.printStackTrace();
                            return Mono
                                    .just(modelMapper.map(server, ServerWithStatsDto.class).setUsage(new StatsDto()));
                        })//
                );
    }

    public Mono<ServerWithStatsDto> getServer(UUID id) {
        var container = findContainerByServerId(id);

        if (container == null) {
            return Mono.just(new ServerWithStatsDto().setStatus("DELETED"));
        }

        var containerStats = stats.get(id);
        var metadata = readMetadataFromContainer(container).orElseThrow();

        return stats(id)//
                .map(stats -> {
                    var dto = modelMapper.map(metadata.getInit(), ServerWithStatsDto.class);
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

        String containerId = null;

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

        attachToLogs(containerId, request.getInit().getId());

        return serverGateway//
                .ok()
                .then(serverGateway.isHosting())//
                .flatMap(isHosting -> isHosting //
                        ? Mono.empty()
                        : host(request.getInit().getId(), request.getHost()))
                .then(syncStats(request.getInit().getId()));
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
                        .withCpuPeriod(100_000L)
                        .withCpuQuota(100_000L)
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
        return Paths
                .get(Config.volumeFolderPath, "servers", serverId.toString(), "config",
                        URLDecoder.decode(path, StandardCharsets.UTF_8))
                .toFile();

    }

    public Flux<MapDto> getMaps(UUID serverId) {
        var folder = getFile(serverId, "maps");

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
        var modFiles = new Fi(folder).findAll();

        var result = new ArrayList<ModDto>();
        for (var modFile : modFiles) {
            try {
                var mod = loadMod(modFile);
                result.add(mod);
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

    private ModDto loadMod(Fi sourceFile) throws Exception {
        ZipFi rootZip = null;

        try {
            Fi zip = sourceFile.isDirectory() ? sourceFile : (rootZip = new ZipFi(sourceFile));
            if (zip.list().length == 1 && zip.list()[0].isDirectory()) {
                zip = zip.list()[0];
            }

            ModMeta meta = findMeta(zip);

            if (meta == null) {
                log.warn("Mod @ doesn't have a '[mod/plugin].[h]json' file, skipping.", zip);
                throw new ApiError(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid file: No mod.json found.");
            }

            return new ModDto()//
                    .setFilename(sourceFile.name())//
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
                            .setVersion(meta.version));
        } catch (Exception e) {
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

    public Mono<Void> sendCommand(UUID serverId, String command) {
        return gatewayService.of(serverId).getServer().sendCommand(command);
    }

    public Mono<Void> hostFromServer(UUID serverId, HostFromSeverRequest request) {
        return initServer(request);
    }

    public Mono<Void> host(UUID serverId, HostServerRequest request) {
        var gateway = gatewayService.of(serverId);

        log.info("Host server: " + serverId);

        return gateway.getServer().isHosting().flatMap(isHosting -> {
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
                            .host(new HostServerRequest()// \
                                    .setMode(request.getMode())
                                    .setHostCommand(request.getHostCommand())))//
                    .then(waitForHosting(gateway))
                    .then(syncStats(serverId));
        });
    }

    private Mono<Void> waitForHosting(GatewayClient gateway) {
        return gateway.getServer().isHosting()//
                .flatMap(isHosting -> isHosting //
                        ? Mono.empty()
                        : ApiError.badRequest("Server is not hosting yet"))//
                .retryWhen(Retry.fixedDelay(50, Duration.ofMillis(100)))//
                .then();
    }

    public Mono<Void> ok(UUID serverId) {
        return gatewayService.of(serverId).getServer().ok();
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
                .defaultIfEmpty(new StatsDto().setStatus(container == null //
                        ? "DELETED"
                        : container//
                                .getState()//
                                .equalsIgnoreCase("running")//
                                        ? "NOT_RESPONSE"
                                        : "DOWN"))
                .map(serverStats -> {
                    var containerStats = stats.get(serverId);
                    if (containerStats != null) {
                        serverStats.setCpuUsage(containerStats.cpuUsage())//
                                .setTotalRam(containerStats.totalRam())//
                                .setRamUsage(containerStats.ramUsage());
                    }

                    return serverStats;
                });
    }

    public Mono<StatsDto> detailStats(UUID serverId) {
        var container = findContainerByServerId(serverId);

        if (container == null) {
            return Mono.just(new StatsDto().setStatus("DELETED"));
        }

        if (!container.getState().equalsIgnoreCase("running")) {
            return Mono.just(new StatsDto().setStatus("DOWN"));
        }

        return gatewayService.of(serverId)//
                .getServer()//
                .getDetailStats()//
                .defaultIfEmpty(new StatsDto().setStatus(container == null //
                        ? "DELETED"
                        : container//
                                .getState()//
                                .equalsIgnoreCase("running")//
                                        ? "NOT_RESPONSE"
                                        : "DOWN"))
                .map(serverStats -> {
                    var containerStats = stats.get(serverId);

                    if (containerStats != null) {
                        serverStats.setCpuUsage(containerStats.cpuUsage())//
                                .setTotalRam(containerStats.totalRam())//
                                .setRamUsage(containerStats.ramUsage());
                    }

                    return serverStats;
                });

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
            Sinks.Many<String> newSink = Sinks.many().multicast().onBackpressureBuffer();

            Disposable subscription = newSink.asFlux()
                    .bufferTimeout(10, Duration.ofMillis(100))
                    .concatMap(batch -> gatewayService.of(serverId)//
                            .getBackend()
                            .sendConsole(String.join("\n", batch))) // preserve order
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            null,
                            error -> log.error("Error in log stream for server {}", id, error),
                            () -> log.info("Log stream for server {} completed", id));

            streamSubscriptions.put(id, subscription);
            return newSink;
        });

        sink.tryEmitNext(message).orThrow();
    }

    private synchronized void attachToLogs(String containerId, UUID serverId) {
        if (streamSubscriptions.containsKey(serverId)) {
            return;
        }

        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                sendConsole(serverId, new String(frame.getPayload()));
            }

            @Override
            public void onComplete() {
                System.out.println("[" + serverId + "] Log stream ended.");
                removeConsoleStream(serverId);
            }

            @Override
            public void onError(Throwable throwable) {
                System.err
                        .println("[" + serverId + "] Log stream error: " + throwable.getMessage());
                throwable.printStackTrace();
                removeConsoleStream(serverId);
            }
        };

        dockerClient.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .withFollowStream(true)
                .exec(callback);

        System.out.println("[" + serverId + "] Log stream started.");
    }

    public void removeConsoleStream(UUID serverId) {
        Optional.ofNullable(streamSubscriptions.remove(serverId)).ifPresent(Disposable::dispose);
        consoleStreams.remove(serverId);
    }
}
