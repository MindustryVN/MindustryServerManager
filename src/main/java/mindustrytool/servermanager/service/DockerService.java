package mindustrytool.servermanager.service;

import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.PullResponseItem;

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
        // Check for volume
        boolean volumeExists = dockerClient.listVolumesCmd().exec().getVolumes().stream().allMatch(volume -> volume.getName().equals(Config.DOCKER_DATA_COLUMN_NAME));

        if (!volumeExists) {
            log.info("Volume not exits, creating new volume with name: " + Config.DOCKER_DATA_COLUMN_NAME);

            dockerClient.createVolumeCmd()//
                    .withName(Config.DOCKER_DATA_COLUMN_NAME)//
                    .withDriver("bridge")//
                    .exec();

            log.info("Volume created");
        }

        boolean imageExists = dockerClient.listImagesCmd().exec().stream().allMatch(image -> image.getId().equals(envConfig.docker().mindustryServerImage()));

        if (!imageExists) {
            var resultCallback = new ResultCallback.Adapter<PullResponseItem>();
            dockerClient.pullImageCmd(envConfig.docker().mindustryServerImage()).exec(resultCallback);

            resultCallback.awaitCompletion();
        }

    }
}
