package mindustrytool.servermanager.controller;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;
import mindustrytool.servermanager.config.Config;
import mindustrytool.servermanager.messages.request.SetPlayerMessageRequest;
import mindustrytool.servermanager.messages.response.StatsMessageResponse;
import mindustrytool.servermanager.service.ServerService;
import mindustrytool.servermanager.types.data.Player;
import mindustrytool.servermanager.types.request.HostFromSeverRequest;
import mindustrytool.servermanager.types.request.HostServerRequest;
import mindustrytool.servermanager.types.response.ServerDto;
import mindustrytool.servermanager.types.response.ServerFileDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ServerController {

    private final ServerService serverService;

    @GetMapping("/servers/{id}")
    public Mono<ServerDto> getServer(@PathVariable("id") UUID serverId) {
        return serverService.getServer(serverId);
    }

    @GetMapping("/servers/{id}/players")
    public Flux<Player> getServerPlayers(@PathVariable("id") UUID serverId) {
        return serverService.getPlayers(serverId);
    }

    @GetMapping("/servers")
    public Flux<ServerDto> getServers() {
        return serverService.getServers();
    }

    @GetMapping("/servers/{id}/files")
    Flux<ServerFileDto> getFiles(@PathVariable("id") UUID serverId, @RequestParam("path") String path) {
        return serverService.getFiles(serverId, URLDecoder.decode(path, StandardCharsets.UTF_8));
    }

    @GetMapping("/servers/{id}/files/download")
    public ResponseEntity<Resource> downloadFile(//
            @PathVariable("id") UUID id, @RequestParam(name = "path", required = true) String path) {

        var file = serverService.getFile(id, path);
        try {
            InputStreamResource resource = new InputStreamResource(
                    new FileInputStream(file));

            return ResponseEntity.ok()//
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            String.format("attachment; filename=%s", Paths.get(path).getFileName()))
                    .contentLength(file.length())//
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)//
                    .body(resource);
        } catch (FileNotFoundException e) {
            throw new RuntimeException("File not found");
        }
    }

    @PostMapping(value = "/servers/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Mono<Void> createFile(@PathVariable("id") UUID serverId, @RequestPart("path") String path,
            @RequestPart("file") FilePart file) {
        return serverService.createFile(serverId, file, path);
    }

    @DeleteMapping("/servers/{id}/files")
    Mono<Void> deleteFile(@PathVariable("id") UUID serverId, @RequestParam("path") String path) {
        return serverService.deleteFile(serverId, URLDecoder.decode(path, StandardCharsets.UTF_8));
    }

    @PostMapping("/servers/{id}/command")
    public Mono<Void> sendCommand(@PathVariable("id") UUID serverId, @RequestBody String command) {
        return serverService.sendCommand(serverId, command);
    }

    @PostMapping("/servers/{id}/host")
    public Mono<Void> host(@PathVariable("id") UUID serverId, @Validated @RequestBody HostServerRequest request) {
        return serverService.host(serverId, request);
    }

    @PostMapping("/servers/{id}/host-from-server")
    public Mono<Void> hostFromServer(@PathVariable("id") UUID serverId,
            @Validated @RequestBody HostFromSeverRequest request) {
        return serverService.hostFromServer(serverId, request).subscribeOn(Config.QUEUE_SCHEDULER);
    }

    @PostMapping("/servers/{id}/set-player")
    public Mono<Void> setPlayer(@PathVariable("id") UUID serverId, @RequestBody SetPlayerMessageRequest request) {
        return serverService.setPlayer(serverId, request);
    }

    @GetMapping("/servers/{id}/stats")
    public Mono<StatsMessageResponse> stats(@PathVariable("id") UUID serverId) {
        return serverService.stats(serverId);
    }

    @GetMapping("/servers/{id}/shutdown")
    public Mono<Void> shutdown(@PathVariable("id") UUID serverId) {
        return serverService.shutdown(serverId);
    }

    @GetMapping("/servers/{id}/remove")
    public Mono<Void> remove(@PathVariable("id") UUID serverId) {
        return serverService.remove(serverId);
    }

    @GetMapping("/servers/{id}/detail-stats")
    public Mono<StatsMessageResponse> detailStats(@PathVariable("id") UUID serverId) {
        return serverService.detailStats(serverId);
    }

    @GetMapping("/servers/{id}/ok")
    public Mono<Void> ok(@PathVariable("id") UUID serverId) {
        return serverService.ok(serverId);
    }

    @PutMapping("servers/{id}/config")
    public Mono<JsonNode> setConfig(//
            @PathVariable("id") UUID serverId, //
            @RequestPart("key") String key,
            @RequestPart("value") String value//
    ) {
        return serverService.setConfig(serverId, key, value);
    }
}
