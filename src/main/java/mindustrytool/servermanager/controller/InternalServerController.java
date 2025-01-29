package mindustrytool.servermanager.controller;

import java.util.UUID;

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
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/internal-api/v1")
@RequiredArgsConstructor
public class InternalServerController {

    @PostMapping(value = "players", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<SetPlayerMessageRequest> setPlayer(@RequestBody PlayerMessageRequest payload) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().setPlayer(payload));
    }

    @GetMapping("total-player")
    public Mono<Integer> getTotalPlayer() {
        return ServerFilter.getContext().map(server -> server.getPlayers().size());
    }

    @PostMapping("chat")
    public Mono<Void> sendChat(@RequestBody String chat) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().sendChat(chat));
    }

    @PostMapping("console")
    public Mono<Void> sendConsole(@RequestBody String console) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().sendConsole(console));
    }

    @PostMapping(value = "host", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> host(@RequestBody UUID serverId) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().host(serverId));
    }

    @PostMapping(value = "player-leave", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Void> onPlayerLeave(@RequestBody PlayerMessageRequest request) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().onPlayerLeave(request));
    }

    @PostMapping(value = "player-join", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Void> onPlayerJoin(@RequestBody PlayerMessageRequest request) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().onPlayerJoin(request));
    }

    @GetMapping("servers")
    public Mono<GetServersMessageResponse> getServers(@RequestParam("page") int page, @RequestParam("size") int size) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().getServers(page, size));
    }
}
