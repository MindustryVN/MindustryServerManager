package mindustrytool.servermanager;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record EnvConfig() {
}
