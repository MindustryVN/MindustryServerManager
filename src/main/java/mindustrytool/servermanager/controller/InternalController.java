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
public class InternalController {

    @PostMapping("players")
    public Mono<SetPlayerMessageRequest> setPlayer(@RequestBody PlayerMessageRequest payload) {
        return ServerFilter.getContext().flatMap(server -> server.getServers(payload));
    }
}
