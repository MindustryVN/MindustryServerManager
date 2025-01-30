package mindustrytool.servermanager.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.modelmapper.ModelMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mindustrytool.servermanager.EnvConfig;
import mindustrytool.servermanager.config.Config;
import mindustrytool.servermanager.messages.request.SetPlayerMessageRequest;
import mindustrytool.servermanager.messages.request.StartServerMessageRequest;
import mindustrytool.servermanager.messages.response.StatsMessageResponse;
import mindustrytool.servermanager.types.data.Player;
import mindustrytool.servermanager.types.data.ServerInstance;
import mindustrytool.servermanager.types.request.HostFromSeverRequest;
import mindustrytool.servermanager.types.request.InitServerRequest;
import mindustrytool.servermanager.types.response.ServerDto;
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

    public final ConcurrentHashMap<UUID, ServerInstance> servers = new ConcurrentHashMap<>();

    public ServerInstance getServerById(UUID serverId) {
        return servers.get(serverId);
    }

    public long getTotalPlayers() {
        return servers.values()//
                .stream()//
                .mapToLong(i -> i.getPlayers().stream().filter(s -> s.getLeaveAt() == null).count())//
                .sum();
    }

    private boolean shouldShutdownServer(ServerInstance server) {
        return server.isAutoTurnOff() && server.getPlayers().size() == 0;
    }

    private void handleServerShutdown(ServerInstance server) {
        var shouldShowdown = shouldShutdownServer(server);
        if (shouldShowdown) {
            if (server.isKillFlag()) {
                shutdown(server.getId()).subscribe();
            } else {
                server.setKillFlag(true);
            }
        } else {
            server.setKillFlag(false);
        }
    }

    @Scheduled(fixedDelay = 600000)
    private void shutdownNoPlayerServer() {
        servers.values()//
                .stream()//
                .sorted((o1, o2) -> o1.getInitiatedAt().getNano() - o2.getInitiatedAt().getNano())//
                .forEach(server -> handleServerShutdown(server));
    }

    @PostConstruct
    private void init() {
        loadRunningServers();

    }

    public List<Container> findContainerByServerId(UUID serverId) {
        return dockerClient.listContainersCmd()//
                .withLabelFilter(Map.of(Config.serverIdLabel, serverId.toString()))//
                .withShowAll(true)//
                .exec();
    }

    public Mono<Void> shutdown(UUID serverId) {
        servers.remove(serverId);

        var containers = findContainerByServerId(serverId);

        log.info("Found %s container to stop".formatted(containers.size()));

        return Flux.fromIterable(containers)//
                .doOnNext(container -> {
                    dockerClient.stopContainerCmd(container.getId()).exec();
                    log.info("Stopped: " + container.getNames()[0]);
                })//
                .then();
    }

    public Flux<ServerDto> getServers() {
        return Flux.fromIterable(servers.values())//
                .flatMap(server -> gatewayService.of(server.getId())//
                        .getServer()//
                        .getStats()//
                        .map(stats -> modelMapper.map(server, ServerDto.class).setUsage(stats))//
                        .onErrorResume(ignore -> Mono.just(modelMapper.map(server, ServerDto.class)))//
                );
    }

    public Mono<ServerDto> getServer(UUID id) {
        return Mono.justOrEmpty(servers.get(id))//
                .flatMap(server -> gatewayService.of(id)//
                        .getServer()//
                        .getStats()//
                        .map(stats -> modelMapper.map(server, ServerDto.class).setUsage(stats)));
    }

    public Flux<Player> getPlayers(UUID id) {
        return gatewayService.of(id).getServer()//
                .getPlayers()//
                .doOnError(error -> log.error("Failed to get players", error))//
                .onErrorResume(error -> Flux.empty());
    }

    public Mono<ServerDto> initServer(InitServerRequest request) {
        if (request.getPort() <= 0) {
            throw new ApiError(HttpStatus.BAD_GATEWAY, "Invalid port number");
        }

        String containerId;
        var server = servers.get(request.getId());

        var containers = dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withLabelFilter(Map.of(Config.serverIdLabel, request.getId().toString()))//
                .exec();

        if (containers.isEmpty()) {
            log.warn("Container " + request.getId() + " got deleted, creating new");
            servers.remove(request.getId());
            containerId = createNewServerContainer(request);
        } else {
            var container = containers.get(0);
            containerId = container.getId();

            var oldRequest = Utils.readJsonAsClass(container.getLabels().get(Config.serverLabelName), InitServerRequest.class);

            if (oldRequest != null && oldRequest.getPort() != request.getPort()) {
                log.info("Found container " + container.getNames()[0] + "with port mismatch, delete container" + container.getState());

                dockerClient.removeContainerCmd(containerId).exec();
                containerId = createNewServerContainer(request);
            } else {
                log.info("Found container " + container.getNames()[0] + " status: " + container.getState());

                if (!container.getState().equalsIgnoreCase("running")) {
                    log.info("Start container " + container.getNames()[0]);
                    dockerClient.startContainerCmd(containerId).exec();
                }
            }
        }

        server = new ServerInstance(request.getId(), request.getUserId(), request.getName(), request.getDescription(), request.getMode(), containerId, request.getPort(), request.isAutoTurnOff(), envConfig);

        servers.put(request.getId(), server);

        log.info("Created server: " + request.getName());

        return gatewayService.of(server.getId())//
                .getServer()//
                .ok()//
                .retryWhen(Retry.fixedDelay(10, Duration.ofSeconds(1)))//
                .thenReturn(modelMapper.map(server, ServerDto.class));
    }

    private String createNewServerContainer(InitServerRequest request) {
        String serverId = request.getId().toString();
        String serverPath = Paths.get(Config.volumeFolderPath, "servers", serverId, "config").toAbsolutePath().toString();

        Volume volume = new Volume("/config");
        Bind bind = new Bind(serverPath, volume);

        ExposedPort tcp = ExposedPort.tcp(Config.DEFAULT_MINDUSTRY_SERVER_PORT);
        ExposedPort udp = ExposedPort.udp(Config.DEFAULT_MINDUSTRY_SERVER_PORT);

        Ports portBindings = new Ports();

        portBindings.bind(tcp, Ports.Binding.bindPort(request.getPort()));
        portBindings.bind(udp, Ports.Binding.bindPort(request.getPort()));

        log.info("Create new container on port " + request.getPort());

        var command = dockerClient.createContainerCmd(envConfig.docker().mindustryServerImage())//
                .withName(request.getId().toString())//
                .withAttachStdout(true)//
                .withLabels(Map.of(Config.serverLabelName, Utils.toJsonString(request), Config.serverIdLabel, request.getId().toString()));

        if (Config.IS_DEVELOPMENT) {
            ExposedPort localTcp = ExposedPort.tcp(9999);
            portBindings.bind(localTcp, Ports.Binding.bindPort(9999));

            command.withExposedPorts(tcp, udp, localTcp)//
                    .withEnv("SERVER_ID=" + serverId, "ENV=DEV")//
                    .withHostConfig(HostConfig.newHostConfig()//
                            .withPortBindings(portBindings)//
                            .withNetworkMode("mindustry-server")//
                            .withBinds(bind));
        } else {
            command.withExposedPorts(tcp, udp)//
                    .withEnv("SERVER_ID=" + serverId)//
                    .withHostConfig(HostConfig.newHostConfig()//
                            .withPortBindings(portBindings)//
                            .withNetworkMode("mindustry-server")//
                            .withBinds(bind));
        }

        var result = command.exec();

        var containerId = result.getId();

        dockerClient.startContainerCmd(containerId).exec();

        return containerId;
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
        var file = new File(Paths.get(Config.volumeFolderPath, "servers", serverId.toString(), "config", path).toString());

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

    private void loadRunningServers() {
        var containers = dockerClient.listContainersCmd()//
                .withLabelFilter(List.of(Config.serverLabelName))//
                .exec();

        log.info("Found running server: " + String.join("-", containers.stream().map(c -> c.getNames()[0] + ":" + c.getState()).toList()));

        for (Container container : containers) {
            try {
                var labels = container.getLabels();
                var request = Utils.readJsonAsClass(labels.get(Config.serverLabelName), InitServerRequest.class);
                int port = request.getPort();

                ServerInstance server = new ServerInstance(request.getId(), request.getUserId(), request.getName(), request.getDescription(), request.getMode(), container.getId(), port, request.isAutoTurnOff(), envConfig);

                servers.put(request.getId(), server);

                log.info("Loaded server: " + request);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public Mono<Void> sendCommand(UUID serverId, String command) {
        return gatewayService.of(serverId).getServer().sendCommand(command);
    }

    public Mono<Void> hostFromServer(UUID serverId, HostFromSeverRequest request) {
        return initServer(request.getInit())//
                .then(gatewayService.of(serverId).getServer().isHosting())//
                .flatMap(isHosting -> isHosting //
                        ? Mono.empty()
                        : host(serverId, request.getHost()));
    }

    public Mono<Void> host(UUID serverId, StartServerMessageRequest request) {
        ServerInstance server = servers.get(serverId);

        if (server == null) {
            return ApiError.badRequest("Server is not running");
        }

        var gateway = gatewayService.of(serverId);

        String[] preHostCommand = { //
                "stop", //
                "config name %s".formatted(server.getName()), //
                "config desc %s".formatted(server.getDescription())//
        };

        if (request.getCommands() != null && !request.getCommands().isBlank()) {
            var commands = request.getCommands().split("\n");

            return gateway.getServer()//
                    .sendCommand(preHostCommand)//
                    .then(gateway.getServer().sendCommand(commands))//
                    .then();
        }

        return gateway.getServer()//
                .sendCommand(preHostCommand)//
                .then(gateway.getServer().host(request));
    }

    public Mono<Void> ok(UUID serverId) {
        return gatewayService.of(serverId).getServer().ok();
    }

    public Mono<StatsMessageResponse> stats(UUID serverId) {
        return gatewayService.of(serverId).getServer().getStats();
    }

    public Mono<StatsMessageResponse> detailStats(UUID serverId) {
        return gatewayService.of(serverId).getServer().getDetailStats();
    }

    public Mono<Void> setPlayer(UUID serverId, SetPlayerMessageRequest payload) {
        return gatewayService.of(serverId).getServer().setPlayer(payload);
    }
}
