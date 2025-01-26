package mindustrytool.servermanager.types.data;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.experimental.Accessors;
import mindustrytool.servermanager.EnvConfig;
import mindustrytool.servermanager.messages.request.PlayerMessageRequest;
import mindustrytool.servermanager.messages.request.SetPlayerMessageRequest;
import reactor.core.publisher.Mono;

@Data
@Accessors(chain = true)
public class MindustryServer {
    private final UUID id;
    private final UUID userId;
    private final String name;
    private final String description;
    private final String mode;
    private final String containerId;
    private final int port;

    @JsonIgnore
    private final EnvConfig envConfig;

    @JsonIgnore
    public void setHeaders(HttpHeaders headers) {
        headers.setBearerAuth("Bearer " + envConfig.serverConfig().accessToken());
        headers.set("X-SERVER-ID", id.toString());
    }

    @JsonIgnore
    public String backendUri(String... resource) {
        return URI.create(envConfig.serverConfig().serverUrl() + "/api/v3/internal/servers/" + String.join("/", resource)).toString();
    }

    @JsonIgnore
    public String serverUri(String... resource) {
        return URI.create("http://" + id.toString() + "-" + port + ":8080/" + String.join("/", resource)).toString();
    }

    @JsonIgnore
    public Mono<SetPlayerMessageRequest> getServers(PlayerMessageRequest payload) {
        return WebClient.create(backendUri("players"))//
                .post()//
                .headers(this::setHeaders)//
                .bodyValue(payload)//
                .retrieve()//
                .bodyToMono(SetPlayerMessageRequest.class);
    }

    @JsonIgnore
    public Mono<Void> sendCommand(String command) {
        return WebClient.create(serverUri("command"))//
                .post()//
                .headers(this::setHeaders)//
                .bodyValue(command)//
                .retrieve()//
                .bodyToMono(String.class)//
                .then();
    }
}
