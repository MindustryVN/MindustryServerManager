package mindustrytool.servermanager.types.data;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.experimental.Accessors;
import mindustrytool.servermanager.EnvConfig;
import mindustrytool.servermanager.config.Config;
import mindustrytool.servermanager.messages.request.PlayerMessageRequest;
import mindustrytool.servermanager.messages.request.SetPlayerMessageRequest;
import reactor.core.publisher.Mono;

@Data
@Accessors(chain = true)
public class ServerInstance {
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
    private final Server server = new Server();
    private final Backend backend = new Backend();

    public class Server {
        @JsonIgnore
        public String serverUri(String... resource) {
            return URI.create((Config.IS_DEVELOPMENT ? "http://localhost:9999/" : "http://" + id.toString() + "-" + port + ":9999/") + String.join("/", resource)).toString();
        }

        public Mono<Void> sendCommand(String command) {
            return WebClient.create(serverUri("command"))//
                    .post()//
                    .bodyValue(command)//
                    .retrieve()//
                    .bodyToMono(String.class)//
                    .then();
        }
    }

    public class Backend {
        public void setHeaders(HttpHeaders headers) {
            headers.setBearerAuth("Bearer " + envConfig.serverConfig().accessToken());
            headers.set("X-SERVER-ID", id.toString());
        }

        public String backendUri(String... resource) {
            return URI.create(envConfig.serverConfig().serverUrl() + "/api/v3/internal-servers/" + String.join("/", resource)).toString();
        }

        public Mono<SetPlayerMessageRequest> getServers(PlayerMessageRequest payload) {
            return WebClient.create(backendUri("players"))//
                    .post()//
                    .headers(this::setHeaders)//
                    .bodyValue(payload)//
                    .retrieve()//
                    .bodyToMono(SetPlayerMessageRequest.class);
        }

        public Mono<Void> sendChat(String chat) {
            return WebClient.create(backendUri("chat"))//
                    .post()//
                    .headers(this::setHeaders)//
                    .bodyValue(chat)//
                    .retrieve()//
                    .bodyToMono(String.class)//
                    .then();
        }

        public Mono<Void> sendConsole(String console) {
            return WebClient.create(backendUri("console"))//
                    .post()//
                    .headers(this::setHeaders)//
                    .bodyValue(console)//
                    .retrieve()//
                    .bodyToMono(String.class)//
                    .then();
        }

        public Mono<Integer> getTotalPlayer() {
            return WebClient.create(backendUri("total-player"))//
                    .post()//
                    .headers(this::setHeaders)//
                    .retrieve()//
                    .bodyToMono(Integer.class);
        }
    }
}
