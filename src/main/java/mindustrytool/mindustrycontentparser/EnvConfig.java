package mindustrytool.mindustrycontentparser;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record EnvConfig(Files files, Boolean init) {
    public record Files(String assetsFolder, String modsFolder) {
    }
}
