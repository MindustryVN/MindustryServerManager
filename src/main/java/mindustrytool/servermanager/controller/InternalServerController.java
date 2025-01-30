package mindustrytool.servermanager.controller;

import java.util.stream.Collectors;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import mindustrytool.servermanager.filter.ServerFilter;
import mindustrytool.servermanager.messages.request.PlayerMessageRequest;
import mindustrytool.servermanager.messages.request.SetPlayerMessageRequest;
import mindustrytool.servermanager.messages.response.GetServersMessageResponse;
import mindustrytool.servermanager.service.GatewayService;
import mindustrytool.servermanager.service.ServerService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/internal-api/v1")
@RequiredArgsConstructor
public class InternalServerController {

    private final ServerService serverService;
    private final GatewayService gatewayService;

    @PostMapping(value = "players", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<SetPlayerMessageRequest> setPlayer(@RequestBody PlayerMessageRequest payload) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().setPlayer(server.getId(), payload));
    }

    @GetMapping("total-player")
    public Mono<Integer> getTotalPlayer() {
        return ServerFilter.getContext()//
                .flatMap(server -> Flux.fromIterable(serverService.servers.values())//
                        .map(s -> s.getId())//
                        .flatMap(id -> gatewayService.of(id)//
                                .getServer()//
                                .getPlayers()//
                                .collectList())//
                        .collect(Collectors.summingInt(players -> players.size())));
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
    public Mono<GetServersMessageResponse> getServers(@RequestParam("page") int page, @RequestParam("size") int size) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().getServers(page, size));
    }
}
