package mindustrytool.servermanager.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import mindustrytool.servermanager.filter.ServerFilter;
import mindustrytool.servermanager.messages.request.PlayerMessageRequest;
import mindustrytool.servermanager.messages.request.SetPlayerMessageRequest;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/internal-api/v1")
@RequiredArgsConstructor
public class InternalServerController {

    @PostMapping("players")
    public Mono<SetPlayerMessageRequest> setPlayer(@RequestBody PlayerMessageRequest payload) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().getServers(payload));
    }

    
    @PostMapping("total-player")
    public Mono<Integer> getTotalPlayer() {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().getTotalPlayer());
    }
    @PostMapping("chat")
    public Mono<Void> sendChat(@RequestBody String chat) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().sendChat(chat));
    }

    @PostMapping("console")
    public Mono<Void> sendConsole(@RequestBody String console) {
        return ServerFilter.getContext().flatMap(server -> server.getBackend().sendConsole(console));
    }
}
