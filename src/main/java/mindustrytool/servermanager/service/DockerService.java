package mindustrytool.servermanager.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.AuthConfig;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mindustrytool.servermanager.EnvConfig;
import mindustrytool.servermanager.config.Config;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerService {

    private final DockerClient dockerClient;
    private final EnvConfig envConfig;

    @PostConstruct
    void init() throws InterruptedException {
        boolean volumeExists = dockerClient.listVolumesCmd()//
                .exec()//
                .getVolumes()//
                .stream()//
                .anyMatch(volume -> volume.getName().equals(Config.DOCKER_DATA_VOLUME_NAME));

        if (!volumeExists) {
            log.info("Volume not exits, creating new volume with name: " + Config.DOCKER_DATA_VOLUME_NAME);

            dockerClient.createVolumeCmd()//
                    .withName(Config.DOCKER_DATA_VOLUME_NAME)//
                    .exec();

            log.info("Volume created");
        }

        boolean imageExists = dockerClient.listImagesCmd()//
                .exec()//
                .stream()//
                .anyMatch(image -> List.of(image.getRepoTags()).contains(envConfig.docker().mindustryServerImage()));

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

    }
}
