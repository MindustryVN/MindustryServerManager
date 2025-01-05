package mindustrytool.servermanager;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.validation.constraints.NotBlank;

@ConfigurationProperties(prefix = "app")
public record EnvConfig(//
        DockerEnv docker, //
        ServerConfig serverConfig//
) {
    public static record DockerEnv(//
            @NotBlank String mindustryServerImage, //
            String serverDataFolder) {
    }

    public static record ServerConfig(//
            Boolean autoPortAssign //
    ) {
    }
}
