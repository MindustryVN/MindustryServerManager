package mindustrytool.servermanager.service;

import java.io.File;
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
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mindustrytool.servermanager.EnvConfig;
import mindustrytool.servermanager.config.Config;
import mindustrytool.servermanager.types.data.MindustryServer;
import mindustrytool.servermanager.types.request.HostServerRequest;
import mindustrytool.servermanager.types.request.InitServerRequest;
import mindustrytool.servermanager.types.response.ServerDto;
import mindustrytool.servermanager.utils.ApiError;
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
            return Mono.just(modelMapper.map(server, ServerDto.class));
        }

        if (request.getPort() == 0 && !envConfig.serverConfig().autoPortAssign()) {
            return ApiError.badRequest("Port must be specified or autoPortAssign must be on");
        }

        int port = request.getPort() != 0 ? request.getPort() : findFreePort();

        var containers = dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withNameFilter(List.of(request.getId().toString()))//
                .exec();

        Container container = null;

        if (containers.isEmpty()) {
            String requestId = request.getId().toString();
            String serverPath = Paths.get(Config.volumeFolderPath, "servers", requestId).toAbsolutePath().toString();

            try {
                Volume volume = new Volume("/data");
                Bind bind = new Bind(serverPath, volume);

                ExposedPort tcp = ExposedPort.tcp(port);
                Ports portBindings = new Ports();
                portBindings.bind(tcp, Ports.Binding.bindPort(Config.DEFAULT_MINDUSTRY_SERVER_PORT));

                var result = dockerClient.createContainerCmd(envConfig.docker().mindustryServerImage())//
                        .withName(requestId)//
                        .withEnv("SERVER_ID=%s".formatted(requestId))//
                        .withExposedPorts(new ExposedPort(port))//
                        .withHostConfig(HostConfig.newHostConfig().withPortBindings(portBindings).withBinds(bind))//
                        .exec();

                container = dockerClient.listContainersCmd()//
                        .withIdFilter(List.of(result.getId()))//
                        .exec()//
                        .stream()//
                        .findFirst()//
                        .orElseThrow();

            } catch (Exception e) {
                throw new RuntimeException("Failed to create Docker container for request ID: " + requestId, e);
            }
        } else {
            container = containers.get(0);
        }

        if (!container.getState().equalsIgnoreCase("running")) {
            dockerClient.startContainerCmd(container.getId()).exec();
        }

        MindustryServer server = new MindustryServer(request.getId(), request.getUserId(), request.getName(), request.getDescription(), request.getMode(), container.getId(), port);

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

    public Mono<Void> createFile(UUID serverId, FilePart file, String path) {
        var folderPath = Paths.get(Config.volumeFolderPath, "servers", serverId.toString(), "config", path);

        File folder = new File(folderPath.toUri());

        if (!folder.exists()) {
            folder.mkdirs();
        }

        return file.transferTo(folderPath);
    }

    public Mono<Void> deleteFile(UUID serverId, String path) {
        var file = new File(Paths.get(Config.volumeFolderPath, "servers", serverId.toString(), "config", path).toString());

        if (file.exists()) {
            file.delete();
        }

        return Mono.empty();
    }
}
