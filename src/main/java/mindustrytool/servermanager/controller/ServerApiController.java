package mindustrytool.servermanager.controller;

import java.util.ArrayList;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import mindustrytool.servermanager.filter.ServerFilter;
import mindustrytool.servermanager.service.ServerService;
import mindustrytool.servermanager.types.response.ApiServerDto;
import mindustrytool.servermanager.types.response.BuildLogDto;
import mindustrytool.servermanager.types.response.MindustryPlayerDto;
import mindustrytool.servermanager.types.response.PlayerDto;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/internal-api/v1")
@RequiredArgsConstructor
public class ServerApiController {

    private final ServerService serverService;

    @PostMapping(value = "players", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<MindustryPlayerDto> setPlayer(@RequestBody PlayerDto payload) {
        return ServerFilter.getContext()
                .flatMap(server -> server.getBackend()//
                        .setPlayer(payload)//
                        .doOnNext(_ignore -> serverService
                                .stats(server.getId()).flatMap(stats -> server.getBackend().setStats(stats))
                                .subscribe()));
    }

    @PostMapping(value = "players/leave", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Void> onPlayerLeave(@RequestBody PlayerDto payload) {
        return ServerFilter.getContext().flatMap(
                server -> serverService.stats(server.getId()).flatMap(stats -> server.getBackend().setStats(stats)));
    }

    @PostMapping(value = "build-log", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Void> logBuild(@RequestBody ArrayList<BuildLogDto> payload) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().sendBuildLog(payload));
    }

    @GetMapping("total-player")
    public Mono<Long> getTotalPlayer() {
        return serverService.getTotalPlayers()//
                .onErrorReturn(0L);
    }

    @PostMapping("chat")
    public Mono<Void> sendChat(@RequestBody String chat) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().sendChat(chat));
    }

    @PostMapping("console")
    public Mono<Void> sendConsole(@RequestBody String console) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().sendConsole(console));
    }

    @PostMapping(value = "host", consumes = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> host(@RequestBody String serverId) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().host(serverId));
    }

    @GetMapping("servers")
    public Mono<ApiServerDto> getServers(@RequestParam("page") int page, @RequestParam("size") int size) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().getServers(page, size));
    }

    @PostMapping("translate/{targetLanguage}")
    public Mono<String> translate(//
            @PathVariable("targetLanguage") String targetLanguage, //
            @Validated @RequestBody @Size(min = 1, max = 1024) String text//
    ) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().translate(text, targetLanguage));
    }
}
