package mindustrytool.servermanager.service;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.modelmapper.ModelMapper;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Volume;

import lombok.RequiredArgsConstructor;
import mindustrytool.servermanager.EnvConfig;
import mindustrytool.servermanager.config.Config;
import mindustrytool.servermanager.types.data.MindustryServer;
import mindustrytool.servermanager.types.request.InitServerRequest;
import mindustrytool.servermanager.types.response.ServerDto;
import mindustrytool.servermanager.utils.ApiError;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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

        var containers = dockerClient.listContainersCmd().withNameFilter(List.of(request.getId().toString())).exec();

        String containerId = null;

        if (!containers.isEmpty()) {
            var result = dockerClient.createContainerCmd(envConfig.docker().mindustryServerImage())//
                    .withName(request.getId().toString())//
                    .withExposedPorts(ExposedPort.parse(port + ":" + Config.DEFAULT_MINDUSTRY_SERVER_PORT))//
                    .withVolumes(Volume.parse(Map.of(Config.DOCKER_DATA_VOLUME_NAME, request.getId().toString())))//
                    .exec();

            containerId = result.getId();
        } else {
            containerId = containers.get(0).getId();
        }

        dockerClient.startContainerCmd(containerId).exec();

        MindustryServer server = new MindustryServer(request.getId(), request.getUserId(), request.getName(), request.getDescription(), request.getMode(), containerId, port);

        servers.put(request.getId(), server);

        return Mono.just(modelMapper.map(server, ServerDto.class));
    }

    public Mono<Void> stopServer(UUID serverId) {
        MindustryServer server = servers.get(serverId);

        if (server == null) {
            return ApiError.badRequest("Server is not running");
        }

        return Mono.empty();
    }

    public Mono<Void> createFile(UUID serverId, FilePart file) {
        return file.transferTo(Paths.get(Config.volumeFolderPath, "servers", serverId.toString()));
    }
}
