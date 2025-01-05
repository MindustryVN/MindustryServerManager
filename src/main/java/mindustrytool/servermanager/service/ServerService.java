package mindustrytool.servermanager.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Volume;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import mindustrytool.servermanager.EnvConfig;
import mindustrytool.servermanager.config.Config;
import mindustrytool.servermanager.types.data.MindustryServer;
import mindustrytool.servermanager.types.request.CreateServerRequest;
import mindustrytool.servermanager.types.response.ServerDto;
import mindustrytool.servermanager.utils.ApiError;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class ServerService {

    private final DockerClient dockerClient;
    private final EnvConfig envConfig;

    @Getter
    private ConcurrentHashMap<UUID, MindustryServer> servers = new ConcurrentHashMap<>();

    private int findFreePort() {
        int port = Config.DEFAULT_MINDUSTRY_SERVER_PORT;

        var ports = servers.values().stream().map(server -> server.getPort()).toList();

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

    public Mono<ServerDto> createServer(CreateServerRequest request) {

        if (request.getPort() == 0 && !envConfig.serverConfig().autoPortAssign()) {
            return ApiError.badRequest("Port must be specified or autoPortAssign must be on");
        }

        int port = request.getPort() != 0 ? request.getPort() : findFreePort();

        dockerClient.createContainerCmd(envConfig.docker().mindustryServerImage())//
                .withName(request.getName())//
                .withExposedPorts(ExposedPort.parse(request.getPort() + ":" + Config.DEFAULT_MINDUSTRY_SERVER_PORT))//
                .withVolumes(Volume.parse(Map.of(Config.DOCKER_DATA_COLUMN_NAME, Config.DOCKER_DATA_COLUMN_NAME)))//
                .exec();

        return Mono.just(new ServerDto());
    }
}
