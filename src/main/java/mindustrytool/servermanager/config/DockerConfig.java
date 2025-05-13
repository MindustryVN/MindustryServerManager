package mindustrytool.servermanager.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;

@Configuration
public class DockerConfig {

    @Bean
    DockerClientConfig defaultConfig() {
        return DefaultDockerClientConfig.createDefaultConfigBuilder()//
                .build();
    }

    @Bean
    DockerHttpClient httpClient(DockerClientConfig config) {
        return new ApacheDockerHttpClient.Builder()//
                .dockerHost(URI.create("unix:///var/run/docker.sock"))//
                .sslConfig(config.getSSLConfig())//
                .maxConnections(20)//
                .connectionTimeout(Duration.ofSeconds(5))//
                .responseTimeout(Duration.ofSeconds(5))//
                .build();
    }

    @Bean
    DockerClient dockerClient(DockerClientConfig config, DockerHttpClient httpClient) {
        return DockerClientImpl.getInstance(config, httpClient);
    }
}
