package mindustrytool.mindustrycontentparser;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.config.EnableWebFlux;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebFlux
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) throws Exception {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.addAllowedOrigin("http://localhost:3000");
        config.addAllowedOrigin("https://mindustry-tool.com");
        config.setAllowCredentials(true);
        config.setMaxAge(Duration.ofDays(1));

        source.registerCorsConfiguration("/**", config);

        http // Since we r using REST csrf are not needed
                .csrf(csrf -> csrf.disable())//
                .cors(cors -> cors.configurationSource(source))//
                .httpBasic(httpBasic -> httpBasic.disable());

        return http.build();
    }
}
