package mindustrytool.servermanager.types.data;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import mindustrytool.servermanager.messages.request.StartServerMessageRequest;
import mindustrytool.servermanager.messages.response.GetServersMessageResponse;
import mindustrytool.servermanager.messages.response.StatsMessageResponse;
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
    private final boolean isAutoTurnOff;

    private String status;

    @JsonIgnore
    private boolean killFlag = false;

    @JsonIgnore
    private final List<Player> players = new ArrayList<>();

    @JsonIgnore
    private final EnvConfig envConfig;

    @JsonIgnore
    private final Server server = new Server();

    @JsonIgnore
    private final Backend backend = new Backend();

    @JsonIgnore
    private final Instant initiatedAt = Instant.now();

    public class Server {
        @JsonIgnore
        public String serverUri(String... resource) {
            return URI.create((Config.IS_DEVELOPMENT ? "http://localhost:9999/" : "http://" + id.toString() + "-" + port + ":9999/") + String.join("/", resource)).toString();
        }

        public Mono<Void> setPlayer(SetPlayerMessageRequest request) {
            return WebClient.create(serverUri("set-player"))//
                    .post()//
                    .bodyValue(request)//
                    .retrieve()//
                    .bodyToMono(String.class)//
                    .then();
        }

        public Mono<Void> ok() {
            return WebClient.create(serverUri("ok"))//
                    .get()//
                    .retrieve()//
                    .bodyToMono(String.class)//
                    .then();
        }

        public Mono<StatsMessageResponse> getStats() {
            return WebClient.create(serverUri("stats"))//
                    .get()//
                    .retrieve()//
                    .bodyToMono(StatsMessageResponse.class);
        }

        public Mono<StatsMessageResponse> getDetailStats() {
            return WebClient.create(serverUri("detail-stats"))//
                    .get()//
                    .retrieve()//
                    .bodyToMono(StatsMessageResponse.class);
        }

        public Mono<Void> sendCommand(String command) {
            return WebClient.create(serverUri("command"))//
                    .post()//
                    .bodyValue(command)//
                    .retrieve()//
                    .bodyToMono(String.class)//
                    .then();
        }

        public Mono<Void> host(StartServerMessageRequest request) {
            return WebClient.create(serverUri("host"))//
                    .post()//
                    .bodyValue(request.setMode(request.getMode().toLowerCase()))//
                    .retrieve()//
                    .bodyToMono(String.class)//
                    .then();
        }

        public Mono<Boolean> isHosting() {
            return WebClient.create(serverUri("hosting"))//
                    .get()//
                    .retrieve()//
                    .bodyToMono(Boolean.class);
        }
    }

    public class Backend {
        public void setHeaders(HttpHeaders headers) {
            headers.setBearerAuth("Bearer " + envConfig.serverConfig().accessToken());
            headers.set("X-SERVER-ID", id.toString());
        }

        public String backendUri(String... resource) {
            return URI.create(envConfig.serverConfig().serverUrl() + "/api/v3/servers/" + String.join("/", resource)).toString();
        }

        public Mono<SetPlayerMessageRequest> setPlayer(PlayerMessageRequest payload) {
            return WebClient.create(backendUri("players"))//
                    .post()//
                    .headers(this::setHeaders)//
                    .bodyValue(payload)//
                    .retrieve()//
                    .bodyToMono(SetPlayerMessageRequest.class);
        }

        public Mono<GetServersMessageResponse> getServers(int page, int size) {
            return WebClient.create(backendUri("servers?page=%s&size=%s".formatted(page, size)))//
                    .get()//
                    .headers(this::setHeaders)//
                    .retrieve()//
                    .bodyToFlux(GetServersMessageResponse.ResponseData.class).collectList()//
                    .map(server -> new GetServersMessageResponse().setServers(server));
        }

        public Mono<String> host(UUID serverId) {
            return WebClient.create(backendUri("servers", serverId.toString(), "host-from-server"))// a
                    .post()//
                    .headers(this::setHeaders)//
                    .retrieve()//
                    .bodyToMono(String.class);
        }

        public Mono<Void> onPlayerLeave(PlayerMessageRequest payload) {
            var player = players.stream().filter(p -> p.getUuid().equals(payload.getUuid())).findFirst().orElse(null);

            if (player == null) {
                player = new Player()//
                        .setUuid(payload.getUuid())//
                        .setName(payload.getName())//
                        .setIp(payload.getIp());
            }

            player.setLeaveAt(Instant.now())//
                    .setCreatedAt(Instant.now());

            return Mono.empty();
        }

        public Mono<Void> onPlayerJoin(PlayerMessageRequest payload) {
            var uuid = payload.getUuid();
            var ip = payload.getIp();
            var name = payload.getName();
            var team = payload.getTeam();

            var exist = players.stream().filter(p -> p.getUuid().equals(uuid)).findFirst().orElse(null);

            if (exist != null) {
                exist.setLeaveAt(null)//
                        .setIp(ip)//
                        .setTeam(team);
            } else {
                Player newPlayer = new Player()//
                        .setUuid(uuid)//
                        .setName(name)//
                        .setIp(ip)//
                        .setTeam(team);

                players.add(newPlayer);
            }

            return Mono.empty();
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
