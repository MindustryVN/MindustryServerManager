package mindustrytool.servermanager.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.AuthConfig;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mindustrytool.servermanager.EnvConfig;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerService {

    private final DockerClient dockerClient;
    private final EnvConfig envConfig;

    @PostConstruct
    void init() {
        try {
            boolean imageExists = dockerClient.listImagesCmd()//
                    .exec()//
                    .stream()//
                    .anyMatch(
                            image -> {
                                if (image.getRepoTags() == null) {
                                    return false;
                                }
                                return List.of(image.getRepoTags()).contains(envConfig.docker().mindustryServerImage());
                            });

            if (!imageExists) {
                log.info("Image not exits, pulling image with name: " + envConfig.docker().mindustryServerImage());
                dockerClient.pullImageCmd(envConfig.docker().mindustryServerImage())//
                        .withAuthConfig(new AuthConfig()//
                                .withUsername(envConfig.docker().username())//
                                .withIdentityToken(envConfig.docker().authToken()))
                        .start()//
                        .awaitCompletion();

                log.info("Image pulled");
            }

        } catch (Exception e) {
            log.error("Failed to pull server image", e);
        }
    }

    public InspectImageResponse getSelf() {
        return dockerClient.inspectImageCmd("ghcr.io/mindustryvn/mindustry-content-server").exec();
    }
}
