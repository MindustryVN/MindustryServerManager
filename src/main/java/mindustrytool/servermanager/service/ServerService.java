package mindustrytool.servermanager.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mindustrytool.servermanager.EnvConfig;
import mindustrytool.servermanager.config.Config;
import mindustrytool.servermanager.messages.request.SetPlayerMessageRequest;
import mindustrytool.servermanager.messages.request.StartServerMessageRequest;
import mindustrytool.servermanager.messages.response.StatsMessageResponse;
import mindustrytool.servermanager.service.GatewayService.GatewayClient;
import mindustrytool.servermanager.types.data.Player;
import mindustrytool.servermanager.types.request.HostFromSeverRequest;
import mindustrytool.servermanager.types.request.InitServerRequest;
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

    public final ConcurrentHashMap<UUID, Boolean> servers = new ConcurrentHashMap<>();

    private final Long MAX_FILE_SIZE = 5000000l;

    private Mono<Boolean> shouldShutdownServer(InitServerRequest server) {
        var container = findContainerByServerId(server.getId());

        if (container == null) {
            log.error("Container not found for server {}", server.getId());
            return Mono.just(false);
        }

        if (!container.getState().equalsIgnoreCase("running")) {
            return gatewayService.of(server.getId())//
                    .getBackend()
                    .sendConsole("Auto shut down not running server: " + server.getId())//
                    .thenReturn(true);
        }

        return gatewayService.of(server.getId())//
                .getServer()//
                .getPlayers()//
                .collectList()//
                .map(players -> server.isAutoTurnOff() && players.size() == 0)//
                .retry(5)//
                .onErrorReturn(true);
    }

    private Mono<Void> handleServerShutdown(InitServerRequest server) {
        return shouldShutdownServer(server).flatMap(shouldShutdown -> {
            var backend = gatewayService.of(server.getId()).getBackend();

            var killFlag = servers.getOrDefault(server.getId(), false);

            if (shouldShutdown) {
                if (killFlag) {
                    log.info("Killing server {} due to no player", server.getId());
                    return shutdown(server.getId())//
                            .then(backend.sendConsole("Auto shut down server: " + server.getId()));
                } else {
                    log.info("Server {} has no players, flag to kill.", server.getId());
                    servers.put(server.getId(), true);
                    return backend.sendConsole("Server " + server.getId() + " has no players, flag to kill");
                }
            } else {
                if (killFlag) {
                    servers.put(server.getId(), false);
                    log.info("Remove flag from server {}", server.getId());
                    return backend.sendConsole("Remove kill flag from server  " + server.getId());
                }
            }
            return Mono.empty();
        });
    }

    @Scheduled(fixedDelay = 300000)
    private void shutdownNoPlayerServer() {
        var containers = dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withLabelFilter(List.of(Config.serverLabelName))//
                .exec();

        Flux.fromIterable(containers)//
                .map(container -> Utils.readJsonAsClass(container.getLabels().get(Config.serverLabelName),
                        InitServerRequest.class))
                .flatMap(server -> handleServerShutdown(server)
                        .retry(5)//
                        .doOnError(error -> log.error("Error when shutdown", error))
                        .onErrorResume(ignore -> Mono.empty()))//
                .subscribe();
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
        servers.remove(serverId);

        var container = findContainerByServerId(serverId);

        log.info("Found %s container to stop".formatted(container.getId()));

        dockerClient.stopContainerCmd(container.getId()).exec();
        log.info("Stopped: " + container.getNames()[0]);

        return Mono.empty();

    }

    public Mono<Void> remove(UUID serverId) {
        servers.remove(serverId);

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
                .map(container -> Utils.readJsonAsClass(container.getLabels().get(Config.serverLabelName),
                        InitServerRequest.class))
                .flatMap(server -> gatewayService.of(server.getId())//
                        .getServer()//
                        .getStats()//
                        .map(stats -> modelMapper.map(server, ServerDto.class).setUsage(stats).setStatus(stats.status))//
                        .onErrorResume(ignore -> Mono
                                .just(modelMapper.map(server, ServerDto.class).setUsage(new StatsMessageResponse())))//
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

        var containerOnRequestPort = dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withLabelFilter(List.of(Config.serverLabelName))//
                .exec();

        for (var container : containerOnRequestPort) {

            for (var port : container.getPorts()) {

                var oldRequest = Utils.readJsonAsClass(container.getLabels().get(Config.serverLabelName),
                        InitServerRequest.class);

                if (port.getPublicPort() == request.getPort() && !request.getId().equals(oldRequest.getId())) {
                    log.info("Port " + request.getPort() + " is already used by container: " + container.getId()
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

            var oldRequest = Utils.readJsonAsClass(container.getLabels().get(Config.serverLabelName),
                    InitServerRequest.class);

            if (!oldRequest.equals(request)) {
                log.info("Found container " + container.getNames()[0] + "with config mismatch, delete container");

                if (container.getState().equalsIgnoreCase("running")) {
                    dockerClient.stopContainerCmd(containerId).exec();
                }

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

        log.info("Created server: " + request.getName() + " with " + request);

        return gatewayService.of(request.getId())//
                .getServer()//
                .ok()//
                .retryWhen(Retry.fixedDelay(10, Duration.ofSeconds(1)))//
                .thenReturn(modelMapper.map(server, ServerDto.class));
    }

    private String createNewServerContainer(InitServerRequest request) {
        String serverId = request.getId().toString();
        String serverPath = Paths.get(Config.volumeFolderPath, "servers", serverId, "config").toAbsolutePath()
                .toString();

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
                .withLabels(Map.of(Config.serverLabelName, Utils.toJsonString(request), Config.serverIdLabel,
                        request.getId().toString()));

        var env = new ArrayList<String>();
        var exposedPorts = new ArrayList<ExposedPort>();

        exposedPorts.add(tcp);
        exposedPorts.add(udp);

        env.addAll(request.getEnv().entrySet().stream().map(v -> v.getKey() + "=" + v.getValue()).toList());
        env.add("IS_HUB=" + request.isHub());
        env.add("SERVER_ID=" + serverId);

        if (Config.IS_DEVELOPMENT) {
            env.add("ENV=DEV");
            ExposedPort localTcp = ExposedPort.tcp(9999);
            portBindings.bind(localTcp, Ports.Binding.bindPort(9999));
            exposedPorts.add(localTcp);
        }

        command.withExposedPorts(exposedPorts)//
                .withEnv(env)
                .withHostConfig(HostConfig.newHostConfig()//
                        .withPortBindings(portBindings)//
                        .withNetworkMode("mindustry-server")//
                        .withMemory(419430400l)
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

        return initServer(request.getInit())//
                .then(gatewayService.of(serverId).getServer().isHosting())//
                .flatMap(isHosting -> isHosting //
                        ? Mono.empty()
                        : host(serverId, request.getHost()));
    }

    public Mono<Void> host(UUID serverId, StartServerMessageRequest request) {
        var gateway = gatewayService.of(serverId);

        return gateway.getServer().isHosting().onErrorReturn(false).flatMap(isHosting -> {
            if (isHosting) {
                return Mono.empty();
            }

            var container = findContainerByServerId(serverId);

            if (container == null) {
                return ApiError.badRequest("Server not initialized");
            }

            var server = Utils.readJsonAsClass(container.getLabels().get(Config.serverLabelName),
                    InitServerRequest.class);

            String[] preHostCommand = { //
                    "config name %s".formatted(server.getName()), //
                    "config desc %s".formatted(server.getDescription())//
            };

            if (request.getCommands() != null && !request.getCommands().isBlank()) {
                var commands = request.getCommands().split("\n");

                return shutdown(serverId)//
                        .then(gateway.getServer()//
                                .sendCommand(preHostCommand))//
                        .then(gateway.getServer().sendCommand(commands))//
                        .then(waitForHosting(gateway));
            }

            return shutdown(serverId)//
                    .then(gateway.getServer()//
                            .sendCommand(preHostCommand))//
                    .then(gateway.getServer().host(request))//
                    .then(waitForHosting(gateway));
        });
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
        return gatewayService.of(serverId).getServer()//
                .getStats()//
                .onErrorReturn(getStatIfError(serverId));
    }

    public Mono<StatsMessageResponse> detailStats(UUID serverId) {
        return gatewayService.of(serverId).getServer()//
                .getDetailStats()//
                .onErrorReturn(getStatIfError(serverId));

    }

    private StatsMessageResponse getStatIfError(UUID serverId) {
        var containers = dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withLabelFilter(Map.of(Config.serverIdLabel, serverId.toString()))//
                .exec();

        var status = !containers.isEmpty() && containers.get(0).getState().equalsIgnoreCase("running") ? "UP" : "DOWN";

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
}
