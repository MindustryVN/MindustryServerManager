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

    private ConcurrentHashMap<UUID, ServerInstance> servers = new ConcurrentHashMap<>();

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
                shutdown(server.getId());
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

    public Flux<Player> getPlayers(UUID id) {
        var instance = servers.get(id);

        if (instance == null) {
            return Flux.empty();
        }

        return Flux.fromIterable(instance.getPlayers())//
                .filter(player -> player.getLeaveAt() == null);
    }

    @PostConstruct
    private void init() {
        loadRunningServers();

    }

    public List<Container> findContainerByServerId(UUID serverId) {
        return dockerClient.listContainersCmd()//
                .exec()//
                .stream()//
                .filter(container -> List.of(container.getNames())//
                        .stream()//
                        .anyMatch(name -> name.startsWith(serverId.toString())))//
                .toList();
    }

    public void shutdown(UUID serverId) {
        servers.remove(serverId);

        var containers = findContainerByServerId(serverId);

        for (var container : containers) {
            dockerClient.stopContainerCmd(container.getId()).exec();
        }
    }

    public Flux<ServerDto> getServers() {
        return Flux.fromIterable(servers.values())//
                .flatMap(server -> server.getServer()//
                        .getStats()//
                        .map(stats -> modelMapper.map(server, ServerDto.class).setUsage(stats)));
    }

    public Mono<ServerDto> getServer(UUID id) {
        return Mono.justOrEmpty(servers.get(id))//
                .flatMap(server -> server.getServer()//
                        .getStats()//
                        .map(stats -> modelMapper.map(server, ServerDto.class).setUsage(stats)));
    }

    public Mono<ServerDto> initServer(InitServerRequest request) {
        if (servers.containsKey(request.getId())) {
            var server = servers.get(request.getId());
            String dockerContainerName = request.getId().toString() + "-" + server.getPort();

            var containers = dockerClient.listContainersCmd()//
                    .withShowAll(true)//
                    .withNameFilter(List.of(dockerContainerName))//
                    .exec();

            if (!containers.isEmpty()) {
                var container = containers.get(0);

                if (!container.getState().equalsIgnoreCase("running")) {
                    log.info("Start container " + container.getNames());
                    dockerClient.startContainerCmd(container.getId()).exec();
                }
            } else {
                log.warn("Container " + dockerContainerName + " is not running");
                servers.remove(request.getId());

                return Mono.error(new RuntimeException("Container is not running"));
            }

            return Mono.just(modelMapper.map(server, ServerDto.class));
        }

        if (request.getPort() == 0 && !envConfig.serverConfig().autoPortAssign()) {
            return ApiError.badRequest("Port must be specified or autoPortAssign must be on");
        }

        int port = request.getPort();
        String dockerContainerName = request.getId().toString() + "-" + port;

        var containers = dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withNameFilter(List.of(dockerContainerName))//
                .exec();

        String containerId;

        if (containers.isEmpty()) {
            String serverId = request.getId().toString();
            String serverPath = Paths.get(Config.volumeFolderPath, "servers", serverId, "config").toAbsolutePath().toString();

            Volume volume = new Volume("/config");
            Bind bind = new Bind(serverPath, volume);

            ExposedPort tcp = ExposedPort.tcp(port);
            ExposedPort udp = ExposedPort.udp(port);

            Ports portBindings = new Ports();

            portBindings.bind(tcp, Ports.Binding.bindPort(Config.DEFAULT_MINDUSTRY_SERVER_PORT));
            portBindings.bind(udp, Ports.Binding.bindPort(Config.DEFAULT_MINDUSTRY_SERVER_PORT));

            log.info("Create new container on port " + port);

            var command = dockerClient.createContainerCmd(envConfig.docker().mindustryServerImage())//
                    .withName(dockerContainerName)//
                    .withAttachStdout(true)//
                    .withLabels(Map.of(Config.serverLabelName, Utils.toJsonString(request)));

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

            containerId = result.getId();
            dockerClient.startContainerCmd(containerId).exec();

        } else {
            var container = containers.get(0);
            containerId = container.getId();

            if (!container.getState().equalsIgnoreCase("running")) {
                log.info("Start container " + container.getNames());
                dockerClient.startContainerCmd(containerId).exec();
            }
        }

        ServerInstance server = new ServerInstance(request.getId(), request.getUserId(), request.getName(), request.getDescription(), request.getMode(), containerId, port, request.isAutoTurnOff(), envConfig);

        servers.put(request.getId(), server);

        log.info("Created server: " + request.getName());

        return server.getServer()//
                .isHosting()//
                .retryWhen(Retry.fixedDelay(10, Duration.ofSeconds(1)))//
                .thenReturn(modelMapper.map(server, ServerDto.class));
    }

    public Mono<Void> stopServer(UUID serverId) {
        ServerInstance server = servers.get(serverId);

        if (server == null) {
            return ApiError.badRequest("Server is not running");
        }

        return Mono.empty();
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

        if (file.exists()) {
            file.delete();
        }

        return Mono.empty();
    }

    private void loadRunningServers() {
        var containers = dockerClient.listContainersCmd()//
                .withLabelFilter(List.of(Config.serverLabelName))//
                .exec();

        for (Container container : containers) {
            var labels = container.getLabels();

            if (labels == null || !labels.containsKey(Config.serverLabelName)) {
                log.warn("Skip container " + container.getId() + " because it does not have label " + Config.serverLabelName);
                continue;
            }

            if (!container.getState().equalsIgnoreCase("running")) {
                log.info("Starting container " + container.getId());
                dockerClient.startContainerCmd(container.getId()).exec();
            }

            var request = Utils.readJsonAsClass(labels.get(Config.serverLabelName), InitServerRequest.class);

            String containerName = container.getNames()[0];

            int port = Integer.parseInt(containerName.substring(containerName.lastIndexOf('-') + 1));
            ServerInstance server = new ServerInstance(request.getId(), request.getUserId(), request.getName(), request.getDescription(), request.getMode(), container.getId(), port, request.isAutoTurnOff(), envConfig);

            servers.put(request.getId(), server);
        }
    }

    public Mono<Void> sendCommand(UUID serverId, String command) {
        ServerInstance server = servers.get(serverId);

        if (server == null) {
            return ApiError.badRequest("Server is not running");
        }

        return server.getServer().sendCommand(command);
    }

    public Mono<Void> host(UUID serverId, StartServerMessageRequest request) {
        ServerInstance server = servers.get(serverId);

        if (server == null) {
            return ApiError.badRequest("Server is not running");
        }

        var preHostCommand = "stop \nconfig port %s \n config name %s \nconfig description %s".formatted(server.getPort(), server.getName(), server.getDescription());

        if (request.getCommands() != null && !request.getCommands().isBlank()) {
            var commands = request.getCommands().split("\n");

            return server.getServer().sendCommand(preHostCommand).thenMany(Flux.fromArray(commands)).concatMap(command -> server.getServer().sendCommand(command)).then();
        }

        return server.getServer().sendCommand(preHostCommand).then(server.getServer().startServer(request));
    }

    public Mono<Void> ok(UUID serverId) {
        ServerInstance server = servers.get(serverId);

        if (server == null) {
            return ApiError.badRequest("Server is not running");
        }

        return server.getServer().ok();
    }

    public Mono<StatsMessageResponse> stats(UUID serverId) {
        ServerInstance server = servers.get(serverId);

        if (server == null) {
            return Mono.just(new StatsMessageResponse().setPlayers(0).setStatus("DOWN"));
        }

        return server.getServer().getStats();
    }

    public Mono<StatsMessageResponse> detailStats(UUID serverId) {
        ServerInstance server = servers.get(serverId);

        if (server == null) {
            return Mono.just(new StatsMessageResponse().setPlayers(0).setStatus("DOWN"));
        }

        return server.getServer().getDetailStats();
    }

    public Mono<Void> setPlayer(UUID serverId, SetPlayerMessageRequest payload) {
        ServerInstance server = servers.get(serverId);

        if (server == null) {
            return ApiError.badRequest("Server is not running");
        }

        return server.getServer().setPlayer(payload);

    }
}
