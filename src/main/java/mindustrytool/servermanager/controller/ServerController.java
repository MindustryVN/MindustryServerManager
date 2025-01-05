package mindustrytool.servermanager.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import mindustrytool.servermanager.service.ServerService;
import mindustrytool.servermanager.types.request.CreateServerRequest;
import mindustrytool.servermanager.types.response.ServerDto;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ServerController {

    private final ServerService serverService;

    @GetMapping("/servers")
    public String getServers() {
        return "List of servers";
    }

    @PostMapping("/servers")
    Mono<ServerDto> createServer(@Validated @RequestBody CreateServerRequest request) {
        return serverService.createServer(request);
    }
}
