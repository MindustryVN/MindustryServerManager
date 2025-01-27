package mindustrytool.servermanager.controller;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import mindustrytool.servermanager.messages.request.SetPlayerMessageRequest;
import mindustrytool.servermanager.messages.request.StartServerMessageRequest;
import mindustrytool.servermanager.messages.response.StatsMessageResponse;
import mindustrytool.servermanager.service.ServerService;
import mindustrytool.servermanager.types.request.InitServerRequest;
import mindustrytool.servermanager.types.response.ServerDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ServerController {

    private final ServerService serverService;

    @GetMapping("/servers")
    public Flux<ServerDto> getServers() {
        return serverService.getServers();
    }

    @PostMapping("/servers/{id}/init")
    Mono<ServerDto> initServer(@Validated @RequestBody InitServerRequest request) {
        return serverService.initServer(request);
    }

    @PostMapping(value = "/servers/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Mono<Void> createFile(@PathVariable("id") UUID serverId, @RequestPart("path") String path, @RequestPart("file") FilePart file) {
        return serverService.createFile(serverId, file, path);
    }

    @DeleteMapping("/servers/{id}/files")
    Mono<Void> deleteFile(@PathVariable("id") UUID serverId, @RequestParam("path") String path) {
        return serverService.deleteFile(serverId, path);
    }

    @PostMapping("/servers/{id}/command")
    public Mono<Void> sendCommand(@PathVariable("id") UUID serverId, @RequestBody String command) {
        return serverService.sendCommand(serverId, command);
    }

    @PostMapping("/servers/{id}/host")
    public Mono<Void> host(@PathVariable("id") UUID serverId, @RequestBody StartServerMessageRequest request) {
        return serverService.host(serverId, request);
    }

    @PostMapping("/servers/{id}/set-player")
    public Mono<Void> setPlayer(@PathVariable("id") UUID serverId, @RequestBody SetPlayerMessageRequest request) {
        return serverService.setPlayer(serverId, request);
    }

    @GetMapping("/servers/{id}/stats")
    public Mono<StatsMessageResponse> stats(@PathVariable("id") UUID serverId) {
        return serverService.stats(serverId);
    }

    @GetMapping("/servers/{id}/ok")
    public Mono<StatsMessageResponse> detailStats(@PathVariable("id") UUID serverId) {
        return serverService.detailStats(serverId);
    }
}
