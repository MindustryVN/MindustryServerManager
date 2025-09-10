package mindustrytool.servermanager.service;

import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerService {

    private final DockerClient dockerClient;

    public InspectImageResponse getSelf() {
        return dockerClient.inspectImageCmd("ghcr.io/mindustryvn/mindustry-server-manager").exec();
    }
}
