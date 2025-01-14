package mindustrytool.servermanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MindustryServerManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindustryServerManagerApplication.class, args);
    }
}
