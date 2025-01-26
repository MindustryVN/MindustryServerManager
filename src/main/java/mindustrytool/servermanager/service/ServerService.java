package mindustrytool.servermanager.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.modelmapper.ModelMapper;
import org.springframework.http.codec.multipart.FilePart;
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
import mindustrytool.servermanager.types.data.MindustryServer;
import mindustrytool.servermanager.types.request.HostServerRequest;
import mindustrytool.servermanager.types.request.InitServerRequest;
import mindustrytool.servermanager.types.response.ServerDto;
import mindustrytool.servermanager.utils.ApiError;
import mindustrytool.servermanager.utils.Utils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServerService {

    private final DockerClient dockerClient;
    private final EnvConfig envConfig;
    private final ModelMapper modelMapper;

    private ConcurrentHashMap<UUID, MindustryServer> servers = new ConcurrentHashMap<>();

    public MindustryServer getServerById(UUID serverId) {
        return servers.get(serverId);
    }

    @PostConstruct
    private void init() {
        loadRunningServers();

    }

    private int findFreePort() {
        int port = Config.DEFAULT_MINDUSTRY_SERVER_PORT;

        var ports = servers.values()//
                .stream()//
                .map(server -> server.getPort())//
                .toList();

        do {
            if (!ports.contains(port))
                break;

            port++;

        } while (port < Config.MAXIMUM_MINDUSTRY_SERVER_PORT);

        if (port == Config.MAXIMUM_MINDUSTRY_SERVER_PORT) {
            throw new RuntimeException("No available port");
        }

        return port;
    }

    public Flux<ServerDto> getServers() {
        return Flux.fromIterable(servers.values()).map(server -> modelMapper.map(server, ServerDto.class));
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

        int port = request.getPort() != 0 ? request.getPort() : findFreePort();
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
            .withEnv("SERVER_ID=" + serverId)//
            .withLabels(Map.of(Config.serverLabelName, Utils.toJsonString(request)));
            
            if (Config.IS_DEVELOPMENT) {
                ExposedPort localTcp = ExposedPort.tcp(9999);
                portBindings.bind(localTcp, Ports.Binding.bindPort(port));

                command.withExposedPorts(tcp, udp, localTcp)//
                        .withHostConfig(HostConfig.newHostConfig()//
                                .withPortBindings(portBindings)//
                                .withNetworkMode("mindustry-server")//
                                .withBinds(bind));
            } else {
                command.withExposedPorts(tcp, udp)//
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

        MindustryServer server = new MindustryServer(request.getId(), request.getUserId(), request.getName(), request.getDescription(), request.getMode(), containerId, port, envConfig);

        servers.put(request.getId(), server);

        log.info("Created server: " + request.getName());

        return Mono.just(modelMapper.map(server, ServerDto.class));
    }

    public Mono<Void> hostServer(HostServerRequest request) {
        return Mono.empty();
    }

    public Mono<Void> stopServer(UUID serverId) {
        MindustryServer server = servers.get(serverId);

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

            MindustryServer server = new MindustryServer(request.getId(), request.getUserId(), request.getName(), request.getDescription(), request.getMode(), container.getId(), container.getPorts()[0].getPublicPort(), envConfig);

            servers.put(request.getId(), server);
        }
    }

    public Mono<Void> sendCommand(UUID serverId, String command) {
        MindustryServer server = servers.get(serverId);

        if (server == null) {
            return ApiError.badRequest("Server is not running");
        }

        return server.sendCommand(command);
    }

}
