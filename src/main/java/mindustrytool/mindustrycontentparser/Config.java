package mindustrytool.mindustrycontentparser;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Configuration
public class Config implements WebFluxConfigurer {
    @Override
    public void configureHttpMessageCodecs(@SuppressWarnings("null") ServerCodecConfigurer configurer) {
        configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024);
    }
}
