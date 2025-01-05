package mindustrytool.servermanager.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Configuration
public class Config implements WebFluxConfigurer {

    public static String DOCKER_DATA_COLUMN_NAME = "MINDUSTRY_SERVER_DATA";
    public static int DEFAULT_MINDUSTRY_SERVER_PORT = 6567;
    public static int MAXIMUM_MINDUSTRY_SERVER_PORT = 20000;

    @Override
    public void configureHttpMessageCodecs(@SuppressWarnings("null") ServerCodecConfigurer configurer) {
        configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024);
    }
}
