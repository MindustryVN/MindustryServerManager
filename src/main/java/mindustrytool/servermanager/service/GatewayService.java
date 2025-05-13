package mindustrytool.servermanager.service;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mindustrytool.servermanager.EnvConfig;
import mindustrytool.servermanager.config.Config;
import mindustrytool.servermanager.messages.request.PlayerMessageRequest;
import mindustrytool.servermanager.messages.request.SetPlayerMessageRequest;
import mindustrytool.servermanager.messages.request.StartServerMessageRequest;
import mindustrytool.servermanager.messages.response.GetServersMessageResponse;
import mindustrytool.servermanager.messages.response.StatsMessageResponse;
import mindustrytool.servermanager.types.data.Player;
import mindustrytool.servermanager.types.request.BuildLog;
import mindustrytool.servermanager.types.response.ApiServerDto;
import mindustrytool.servermanager.messages.response.ServerCommandDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayService {

    private final EnvConfig envConfig;

    public GatewayClient of(UUID serverId) {
        return new GatewayClient(serverId, envConfig);
    }

    @RequiredArgsConstructor
    public static class GatewayClient {

        @Getter
        private final UUID id;
        private final EnvConfig envConfig;

        @Getter
        private final Backend backend = new Backend();

        @Getter
        private final Server server = new Server();

        public class Server {
            @JsonIgnore
            public String serverUri(String... resource) {
                return URI.create(
                        (Config.IS_DEVELOPMENT ? "http://localhost:9999/" : "http://" + id.toString() + ":9999/")
                                + String.join("/", resource))
                        .toString();
            }

            public Mono<Void> setPlayer(SetPlayerMessageRequest request) {
                return WebClient.create(serverUri("set-player"))//
                        .post()//
                        .bodyValue(request)//
                        .retrieve()//
                        .bodyToMono(String.class)//
                        .timeout(Duration.ofSeconds(5))//
                        .then();
            }

            public Flux<Player> getPlayers() {
                return WebClient.create(serverUri("players"))//
                        .get()//
                        .retrieve()//
                        .bodyToFlux(Player.class)//
                        .timeout(Duration.ofSeconds(5));
            }

            public Mono<Void> ok() {
                return WebClient.create(serverUri("ok"))//
                        .get()//
                        .retrieve()//
                        .bodyToMono(String.class)//
                        .timeout(Duration.ofMillis(100))//
                        .retryWhen(Retry.fixedDelay(100, Duration.ofMillis(500)))//
                        .then();//

            }

            public Mono<StatsMessageResponse> getStats() {
                return WebClient.create(serverUri("stats"))//
                        .get()//
                        .retrieve()//
                        .bodyToMono(StatsMessageResponse.class)//
                        .timeout(Duration.ofSeconds(5));
            }

            public Mono<StatsMessageResponse> getDetailStats() {
                return WebClient.create(serverUri("detail-stats"))//
                        .get()//
                        .retrieve()//
                        .bodyToMono(StatsMessageResponse.class)//
                        .timeout(Duration.ofSeconds(5));
            }

            public Mono<Void> sendCommand(String... command) {
                return WebClient.create(serverUri("commands"))//
                        .post()//
                        .bodyValue(command)//
                        .retrieve()//
                        .bodyToMono(String.class)//
                        .timeout(Duration.ofSeconds(10))//
                        .then();
            }

            public Mono<Void> host(StartServerMessageRequest request) {
                return WebClient.create(serverUri("host"))//
                        .post()//
                        .bodyValue(request.setMode(request.getMode().toLowerCase()))//
                        .retrieve()//
                        .bodyToMono(String.class)//
                        .timeout(Duration.ofSeconds(15))//
                        .then();
            }

            public Mono<Boolean> isHosting() {
                return WebClient.create(serverUri("hosting"))//
                        .get()//
                        .retrieve()//
                        .bodyToMono(Boolean.class)//
                        .timeout(Duration.ofSeconds(1))//
                        .retryWhen(Retry.fixedDelay(50, Duration.ofMillis(100)));
            }

            public Flux<ServerCommandDto> getCommands() {
                return WebClient.create(serverUri("commands"))//
                        .get()//
                        .retrieve()//
                        .bodyToFlux(ServerCommandDto.class)//
                        .timeout(Duration.ofSeconds(10));
            }
        }

        public class Backend {
            public void setHeaders(HttpHeaders headers) {
                headers.setBearerAuth("Bearer " + envConfig.serverConfig().accessToken());
                headers.set("X-SERVER-ID", id.toString());
            }

            public String backendUri(String... resource) {
                return UriComponentsBuilder.fromUriString(
                        String.join("/", envConfig.serverConfig().serverUrl(), "api/v3", String.join("/", resource)))
                        .build().toUriString();
            }

            public Mono<SetPlayerMessageRequest> setPlayer(PlayerMessageRequest payload) {
                return WebClient.create(backendUri("servers", id.toString(), "players"))//
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
                        .bodyToFlux(ApiServerDto.class)//
                        .collectList()//
                        .map(server -> new GetServersMessageResponse().setServers(server));
            }

            public Mono<String> host() {
                return WebClient.create(backendUri("servers", id.toString(), "host-from-server"))//
                        .post()//
                        .headers(this::setHeaders)//
                        .retrieve()//
                        .bodyToMono(String.class);
            }

            public Mono<Void> sendChat(String chat) {
                return WebClient.create(backendUri("servers", id.toString(), "chat"))//
                        .post()//
                        .headers(this::setHeaders)//
                        .bodyValue(chat)//
                        .retrieve()//
                        .bodyToMono(String.class)//
                        .then();
            }

            public Mono<Void> sendBuildLog(ArrayList<BuildLog> logs) {
                return WebClient.create(backendUri("servers", id.toString(), "build-log"))//
                        .post()//
                        .headers(this::setHeaders)//
                        .bodyValue(logs)//
                        .retrieve()//
                        .bodyToMono(Void.class)//
                        .doOnError((error) -> log.error("Fail to send build log", error))
                        .onErrorComplete();
            }

            public Mono<Void> sendConsole(String console) {
                return WebClient.create(backendUri("servers", id.toString(), "console"))//
                        .post()//
                        .headers(this::setHeaders)//
                        .bodyValue(console)//
                        .retrieve()//
                        .bodyToMono(Void.class)//
                        .doOnError((error) -> log.error("Fail to send to console", error))
                        .onErrorComplete();
            }

            public Mono<Integer> getTotalPlayer() {
                return WebClient.create(backendUri("servers", "total-player"))//
                        .post()//
                        .headers(this::setHeaders)//
                        .retrieve()//
                        .bodyToMono(Integer.class);
            }

            public Mono<String> translate(String text, String targetLanguage) {
                return WebClient.create(backendUri("servers", "translate", targetLanguage))//
                        .post()//
                        .bodyValue(text)//
                        .headers(this::setHeaders)//
                        .retrieve()//
                        .bodyToMono(String.class);
            }
        }
    }
}
