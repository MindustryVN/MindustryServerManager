package mindustrytool.servermanager.service;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mindustrytool.servermanager.EnvConfig;
import mindustrytool.servermanager.config.Config;
import mindustrytool.servermanager.types.data.Player;
import mindustrytool.servermanager.types.request.HostServerRequest;
import mindustrytool.servermanager.types.response.ApiServerDto;
import mindustrytool.servermanager.types.response.BuildLogDto;
import mindustrytool.servermanager.types.response.MindustryPlayerDto;
import mindustrytool.servermanager.types.response.PlayerDto;
import mindustrytool.servermanager.types.response.PlayerInfoDto;
import mindustrytool.servermanager.types.response.ServerCommandDto;
import mindustrytool.servermanager.types.response.StatsDto;
import mindustrytool.servermanager.utils.ApiError;
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
        private final Backend backend;

        @Getter
        private final Server server;

        public GatewayClient(UUID id, EnvConfig envConfig) {
            this.id = id;
            this.envConfig = envConfig;

            this.backend = new Backend();
            this.server = new Server();
        }

        private static boolean handleStatus(HttpStatusCode status) {
            return switch (HttpStatus.valueOf(status.value())) {
                case BAD_REQUEST, NOT_FOUND, UNPROCESSABLE_ENTITY, CONFLICT -> true;
                default -> false;
            };
        }

        private static Mono<Throwable> createError(ClientResponse response) {
            return response.bodyToMono(String.class)
                    .map(message -> new ApiError(HttpStatus.valueOf(response.statusCode().value()), message));
        }

        public class Server {
            private final WebClient webClient = WebClient.builder()
                    .baseUrl(URI.create(
                            Config.IS_DEVELOPMENT//
                                    ? "http://localhost:9999/" //
                                    : "http://" + id.toString() + ":9999/")
                            .toString())
                    .defaultStatusHandler(GatewayClient::handleStatus, GatewayClient::createError)
                    .build();

            public Mono<String> getPluginVersion() {
                return webClient.method(HttpMethod.GET)//
                        .uri("plugin-version")//
                        .retrieve()//
                        .bodyToMono(String.class)//
                        .timeout(Duration.ofSeconds(5));
            }

            public Mono<Void> setPlayer(MindustryPlayerDto request) {
                return webClient.method(HttpMethod.POST)//
                        .uri("set-player")//
                        .bodyValue(request)//
                        .retrieve()//
                        .bodyToMono(String.class)//
                        .timeout(Duration.ofSeconds(5))//
                        .then();
            }

            public Flux<Player> getPlayers() {
                return webClient.method(HttpMethod.GET)//
                        .uri("players")//
                        .retrieve()//
                        .bodyToFlux(Player.class)//
                        .timeout(Duration.ofSeconds(5));
            }

            public Mono<Void> ok() {
                return webClient.method(HttpMethod.GET)
                        .uri("ok")
                        .retrieve()//
                        .bodyToMono(String.class)//
                        .timeout(Duration.ofMillis(100))//
                        .retryWhen(Retry.fixedDelay(100, Duration.ofMillis(500)))//
                        .then();//

            }

            public Mono<StatsDto> getStats() {
                return webClient.method(HttpMethod.GET)
                        .uri("stats")
                        .retrieve()//
                        .bodyToMono(StatsDto.class)//
                        .timeout(Duration.ofSeconds(5));
            }

            public Mono<StatsDto> getDetailStats() {
                return webClient.method(HttpMethod.GET)
                        .uri("detail-stats")
                        .retrieve()//
                        .bodyToMono(StatsDto.class)//
                        .timeout(Duration.ofSeconds(5));
            }

            public Mono<Void> sendCommand(String... command) {
                return webClient.method(HttpMethod.POST)
                        .uri("commands")
                        .bodyValue(command)//
                        .retrieve()//
                        .bodyToMono(String.class)//
                        .timeout(Duration.ofSeconds(10))//
                        .then();
            }

            public Mono<Void> host(HostServerRequest request) {
                return webClient.method(HttpMethod.POST)
                        .uri("host")
                        .bodyValue(request.setMode(request.getMode().toLowerCase()))//
                        .retrieve()//
                        .bodyToMono(String.class)//
                        .timeout(Duration.ofSeconds(15))//
                        .then();
            }

            public Mono<Boolean> isHosting() {
                return webClient.method(HttpMethod.GET)
                        .uri("hosting")
                        .retrieve()//
                        .bodyToMono(Boolean.class)//
                        .timeout(Duration.ofMillis(100))//
                        .retryWhen(Retry.fixedDelay(50, Duration.ofMillis(100)));
            }

            public Flux<ServerCommandDto> getCommands() {
                return webClient.method(HttpMethod.GET)
                        .uri("commands")
                        .retrieve()//
                        .bodyToFlux(ServerCommandDto.class)//
                        .timeout(Duration.ofSeconds(10));
            }

            public Flux<PlayerInfoDto> getPlayers(int page, int size, Boolean banned) {
                return webClient.method(HttpMethod.GET)
                        .uri(builder -> builder.path("player-infos")//
                                .queryParam("page", page)
                                .queryParam("size", size)
                                .queryParam("banned", banned)//
                                .build())
                        .retrieve()//
                        .bodyToFlux(PlayerInfoDto.class)//
                        .timeout(Duration.ofSeconds(10));
            }

            public Mono<Map<String, Long>> getKickedIps() {
                return webClient.method(HttpMethod.GET)
                        .uri("kicks")
                        .retrieve()//
                        .bodyToMono(new ParameterizedTypeReference<Map<String, Long>>() {
                        })//
                        .timeout(Duration.ofSeconds(10));
            }

            public Mono<JsonNode> getRoutes() {
                return webClient.method(HttpMethod.GET)
                        .uri("routes")
                        .retrieve()//
                        .bodyToMono(JsonNode.class)//
                        .timeout(Duration.ofSeconds(10));
            }
        }

        public class Backend {
            private final WebClient webClient = WebClient.builder()
                    .baseUrl(URI.create(envConfig.serverConfig().serverUrl() + "/api/v3/").toString())
                    .defaultStatusHandler(GatewayClient::handleStatus, GatewayClient::createError)
                    .defaultHeaders(headers -> {
                        headers.setBearerAuth("Bearer " + envConfig.serverConfig().accessToken());
                        headers.set("X-SERVER-ID", id.toString());
                    })
                    .build();

            public Mono<MindustryPlayerDto> setPlayer(PlayerDto payload) {
                return webClient.method(HttpMethod.POST)
                        .uri("servers/" + id.toString() + "/players")//
                        .bodyValue(payload)//
                        .retrieve()//
                        .bodyToMono(MindustryPlayerDto.class);
            }

            public Mono<Void> setStats(StatsDto payload) {
                return webClient.method(HttpMethod.POST)
                        .uri("servers/" + id.toString() + "/stats")//
                        .bodyValue(payload)//
                        .retrieve()//
                        .bodyToMono(Void.class);
            }

            public Mono<ApiServerDto> getServers(int page, int size) {
                return webClient.method(HttpMethod.GET)//
                        .uri("servers?page=%s&size=%s".formatted(page, size))//
                        .retrieve()//
                        .bodyToFlux(ApiServerDto.class)//
                        .collectList()//
                        .map(server -> new ApiServerDto().setServers(server));
            }

            public Mono<String> host(String serverId) {
                return webClient.method(HttpMethod.POST)
                        .uri("servers/" + serverId + "host-from-server")//
                        .retrieve()//
                        .bodyToMono(String.class);
            }

            public Mono<Void> sendChat(String chat) {
                return webClient.method(HttpMethod.POST)
                        .uri("servers/" + id.toString() + "/chat")//
                        .bodyValue(chat)//
                        .retrieve()//
                        .bodyToMono(String.class)//
                        .then();
            }

            public Mono<Void> sendBuildLog(ArrayList<BuildLogDto> logs) {
                return webClient.method(HttpMethod.POST)//
                        .uri("servers/" + id.toString() + "/build-log")//
                        .bodyValue(logs)//
                        .retrieve()//
                        .bodyToMono(Void.class)//
                        .doOnError((error) -> log.error("Fail to send build log", error))
                        .onErrorComplete();
            }

            public Mono<Void> sendConsole(String console) {
                return webClient.method(HttpMethod.POST)
                        .uri("servers/" + id.toString() + "/console")//
                        .bodyValue(console)//
                        .retrieve()//
                        .bodyToMono(Void.class)//
                        .doOnError((error) -> log.error("Fail to send to console", error))
                        .onErrorComplete();
            }

            public Mono<String> translate(String text, String targetLanguage) {
                return webClient.method(HttpMethod.POST)//
                        .uri("servers/translate/" + targetLanguage)//
                        .bodyValue(text)//
                        .retrieve()//
                        .bodyToMono(String.class);
            }
        }
    }
}
