package mindustrytool.servermanager.service;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
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
import mindustrytool.servermanager.types.response.ServerDto;
import mindustrytool.servermanager.types.response.StatsDto;
import mindustrytool.servermanager.utils.ApiError;
import mindustrytool.servermanager.utils.Utils;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayService {

    private final EnvConfig envConfig;
    private final ConcurrentHashMap<UUID, GatewayClient> cache = new ConcurrentHashMap<>();

    public GatewayClient of(UUID serverId) {
        return cache.computeIfAbsent(serverId, _id -> new GatewayClient(serverId, envConfig));
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
            return response.bodyToMono(JsonNode.class)
                    .map(message -> new ApiError(HttpStatus.valueOf(response.statusCode().value()),
                            message.has("message") ? message.get("message").asText() : message.toString()));
        }

        public class Server {
            private final WebClient webClient = WebClient.builder()
                    .codecs(configurer -> configurer
                            .defaultCodecs()
                            .maxInMemorySize(16 * 1024 * 1024))
                    .baseUrl(URI.create(
                            Config.IS_DEVELOPMENT//
                                    ? "http://localhost:9999/" //
                                    : "http://" + id.toString() + ":9999/")
                            .toString())
                    .defaultStatusHandler(GatewayClient::handleStatus, GatewayClient::createError)
                    .build();

            public Mono<JsonNode> getJson() {
                return webClient.method(HttpMethod.GET)//
                        .uri("json")//
                        .retrieve()//
                        .bodyToMono(JsonNode.class)//
                        .timeout(Duration.ofSeconds(15))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when get json"));
            }

            public Mono<String> getPluginVersion() {
                return webClient.method(HttpMethod.GET)//
                        .uri("plugin-version")//
                        .retrieve()//
                        .bodyToMono(String.class)//
                        .timeout(Duration.ofSeconds(5))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when get plugin version"));
            }

            public Mono<Void> setPlayer(MindustryPlayerDto request) {
                return webClient.method(HttpMethod.POST)//
                        .uri("set-player")//
                        .bodyValue(request)//
                        .retrieve()//
                        .bodyToMono(String.class)//
                        .timeout(Duration.ofSeconds(5))//
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when set player"))
                        .then();
            }

            public Mono<Boolean> pause() {
                return webClient.method(HttpMethod.POST)//
                        .uri("pause")//
                        .retrieve()//
                        .bodyToMono(Boolean.class)//
                        .timeout(Duration.ofSeconds(5))//
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when pause"));
            }

            public Flux<Player> getPlayers() {
                return webClient.method(HttpMethod.GET)//
                        .uri("players")//
                        .retrieve()//
                        .bodyToFlux(Player.class)//
                        .timeout(Duration.ofSeconds(5))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when get player"));
            }

            public Mono<Void> ok() {
                return webClient.method(HttpMethod.GET)
                        .uri("ok")
                        .retrieve()//
                        .bodyToMono(Void.class)//
                        .timeout(Duration.ofMillis(100))//
                        .retryWhen(Retry.fixedDelay(100, Duration.ofMillis(500)))
                        .onErrorMap(error -> Exceptions.isRetryExhausted(error),
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Fail to check for ok"));//

            }

            public Mono<StatsDto> getStats() {
                return webClient.method(HttpMethod.GET)
                        .uri("stats")
                        .retrieve()//
                        .bodyToMono(StatsDto.class)//
                        .timeout(Duration.ofSeconds(1))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when get stats"));
            }

            public Mono<byte[]> getImage() {
                return webClient.method(HttpMethod.GET)
                        .uri("image")
                        .accept(MediaType.IMAGE_PNG)
                        .retrieve()//
                        .bodyToMono(byte[].class)//
                        .timeout(Duration.ofSeconds(5))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when get image"));
            }

            public Mono<Void> sendCommand(String... command) {
                return webClient.method(HttpMethod.POST)
                        .uri("commands")
                        .bodyValue(command)//
                        .retrieve()//
                        .bodyToMono(Void.class)//
                        .timeout(Duration.ofSeconds(2))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when send message"));
            }

            public Mono<Void> say(String message) {
                return webClient.method(HttpMethod.POST)
                        .uri("say")
                        .bodyValue(message)//
                        .retrieve()//
                        .bodyToMono(Void.class)//
                        .timeout(Duration.ofSeconds(2))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when say"));
            }

            public Mono<Void> host(HostServerRequest request) {
                return webClient.method(HttpMethod.POST)
                        .uri("host")
                        .bodyValue(request.setMode(request.getMode().toLowerCase()))//
                        .retrieve()//
                        .bodyToMono(Void.class)//
                        .timeout(Duration.ofSeconds(15))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when host"));
            }

            public Mono<Boolean> isHosting() {
                return webClient.method(HttpMethod.GET)
                        .uri("hosting")
                        .retrieve()//
                        .bodyToMono(Boolean.class)//
                        .timeout(Duration.ofMillis(100))//
                        .retryWhen(Retry.fixedDelay(50, Duration.ofMillis(100)))
                        .onErrorMap(error -> Exceptions.isRetryExhausted(error),
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Fail to check for hosting"));
            }

            public Flux<ServerCommandDto> getCommands() {
                return webClient.method(HttpMethod.GET)
                        .uri("commands")
                        .retrieve()//
                        .bodyToFlux(ServerCommandDto.class)//
                        .timeout(Duration.ofSeconds(10))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when get commands"));
            }

            public Flux<PlayerInfoDto> getPlayers(int page, int size, Boolean banned, String filter) {
                return webClient.method(HttpMethod.GET)
                        .uri(builder -> builder.path("player-infos")//
                                .queryParam("page", page)
                                .queryParam("size", size)
                                .queryParam("banned", banned)//
                                .queryParam("filter", filter)
                                .build())
                        .retrieve()//
                        .bodyToFlux(PlayerInfoDto.class)//
                        .timeout(Duration.ofSeconds(10))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when get players info"));
            }

            public Mono<Map<String, Long>> getKickedIps() {
                return webClient.method(HttpMethod.GET)
                        .uri("kicks")
                        .retrieve()//
                        .bodyToMono(new ParameterizedTypeReference<Map<String, Long>>() {
                        })//
                        .timeout(Duration.ofSeconds(10))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when get kicks"));
            }

            public Mono<JsonNode> getRoutes() {
                return webClient.method(HttpMethod.GET)
                        .uri("routes")
                        .retrieve()//
                        .bodyToMono(JsonNode.class)//
                        .timeout(Duration.ofSeconds(10))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when get routes"));
            }

            public Mono<JsonNode> getWorkflowNodes() {
                return webClient.method(HttpMethod.GET)
                        .uri("/workflow/nodes")
                        .retrieve()//
                        .bodyToMono(JsonNode.class)//
                        .timeout(Duration.ofSeconds(10))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when get workflow nodes"));
            }

            public Flux<ServerSentEvent<JsonNode>> getWorkflowEvents() {
                return webClient.method(HttpMethod.GET)
                        .uri("/workflow/events")
                        .retrieve()//
                        .bodyToFlux(String.class)
                        .map(json -> {
                            try {
                                JsonNode node = Utils.readString(json);
                                return ServerSentEvent.builder(node).build();
                            } catch (Exception e) {
                                e.printStackTrace();
                                return ServerSentEvent.builder((JsonNode) null).build();
                            }
                        }).timeout(Duration.ofSeconds(10))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when get workflow events"));
            }

            public Mono<JsonNode> emitWorkflowNode(String nodeId) {
                return webClient.method(HttpMethod.GET)
                        .uri("/workflow/nodes" + nodeId + "/emit")
                        .retrieve()//
                        .bodyToMono(JsonNode.class)//
                        .timeout(Duration.ofSeconds(10))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when emit workflow node"));
            }

            public Mono<Long> getWorkflowVersion() {
                return webClient.method(HttpMethod.GET)
                        .uri("/workflow/version")
                        .retrieve()//
                        .bodyToMono(Long.class)//
                        .timeout(Duration.ofSeconds(10))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when get workflow version"));
            }

            public Mono<JsonNode> getWorkflow() {
                return webClient.method(HttpMethod.GET)
                        .uri("/workflow")
                        .retrieve()//
                        .bodyToMono(JsonNode.class)//
                        .timeout(Duration.ofSeconds(10))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when get workflow"));
            }

            public Mono<Void> saveWorkflow(JsonNode payload) {
                return webClient.method(HttpMethod.POST)
                        .uri("/workflow")
                        .bodyValue(payload)
                        .retrieve()//
                        .bodyToMono(Void.class)//
                        .timeout(Duration.ofSeconds(10))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when save workflow"));
            }

            public Mono<JsonNode> loadWorkflow(JsonNode payload) {
                return webClient.method(HttpMethod.POST)
                        .uri("/workflow/load")
                        .bodyValue(payload)
                        .retrieve()//
                        .bodyToMono(JsonNode.class)//
                        .timeout(Duration.ofSeconds(10))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Timeout when load workflow"));
            }
        }

        public class Backend {
            private final WebClient webClient = WebClient.builder()
                    .codecs(configurer -> configurer
                            .defaultCodecs()
                            .maxInMemorySize(16 * 1024 * 1024))
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
                        .bodyToMono(MindustryPlayerDto.class)
                        .timeout(Duration.ofSeconds(2))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Backend timeout when set player"));
            }

            public Mono<Void> setStats(StatsDto payload) {
                return webClient.method(HttpMethod.POST)
                        .uri("servers/" + id.toString() + "/stats")//
                        .bodyValue(payload)//
                        .retrieve()//
                        .bodyToMono(Void.class)
                        .timeout(Duration.ofSeconds(2))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Backend timeout when set stats"));
            }

            public Mono<ApiServerDto> getServers(int page, int size) {
                return webClient.method(HttpMethod.GET)//
                        .uri("servers?page=%s&size=%s".formatted(page, size))//
                        .retrieve()//
                        .bodyToFlux(ServerDto.class)//
                        .collectList()//
                        .map(server -> new ApiServerDto().setServers(server))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Backend timeout when get server"));
            }

            public Mono<String> host(String serverId) {
                return webClient.method(HttpMethod.POST)
                        .uri("servers/" + serverId + "/host-from-server")//
                        .retrieve()//
                        .bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(45))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Backend timeout when send host"));
            }

            public Mono<Void> sendChat(String chat) {
                return webClient.method(HttpMethod.POST)
                        .uri("servers/" + id.toString() + "/chat")//
                        .bodyValue(chat)//
                        .retrieve()//
                        .bodyToMono(Void.class)
                        .timeout(Duration.ofSeconds(2))
                        .onErrorMap(TimeoutException.class,
                                error -> new ApiError(HttpStatus.BAD_REQUEST, "Backend timeout when send chat"));
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
                        .timeout(Duration.ofSeconds(3))
                        .doOnError((error) -> log.error("Fail to send to console", error));

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
