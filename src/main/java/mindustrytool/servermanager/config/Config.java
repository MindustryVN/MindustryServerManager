package mindustrytool.servermanager.config;

import java.io.File;

import org.modelmapper.ModelMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.annotation.PostConstruct;

@Configuration
public class Config implements WebFluxConfigurer {

    public static String DOCKER_DATA_VOLUME_NAME = "MINDUSTRY_SERVER_DATA";

    public static int DEFAULT_MINDUSTRY_SERVER_PORT = 6567;
    public static int MAXIMUM_MINDUSTRY_SERVER_PORT = 20000;

    public static String volumeFolderPath = "/data";
    public static File volumeFolder = new File(volumeFolderPath);

    @PostConstruct
    public void init() {
        volumeFolder.mkdirs();
    }

    @Override
    public void configureHttpMessageCodecs(@SuppressWarnings("null") ServerCodecConfigurer configurer) {
        configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024);
    }

    @Bean
    ModelMapper modelMapper() {
        return new ModelMapper();
    }

    @Bean
    public ObjectMapper getObjectMapper() {
        JavaTimeModule module = new JavaTimeModule();

        return new ObjectMapper(new JsonFactoryBuilder().streamReadConstraints(StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build())//
                .configure(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION, false).build())//
                        .configure(DeserializationFeature.FAIL_ON_UNRESOLVED_OBJECT_IDS, false)//
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)//
                        .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)//
                        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)//
                        .setSerializationInclusion(JsonInclude.Include.NON_NULL)//
                        .registerModule(module);
    }

}
