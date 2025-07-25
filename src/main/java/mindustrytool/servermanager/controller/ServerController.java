package mindustrytool.servermanager.controller;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Map;
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
import mindustrytool.servermanager.service.GatewayService;
import mindustrytool.servermanager.service.ServerService;
import mindustrytool.servermanager.types.data.Player;
import mindustrytool.servermanager.types.request.HostFromSeverRequest;
import mindustrytool.servermanager.types.request.HostServerRequest;
import mindustrytool.servermanager.types.request.InitServerRequest;
import mindustrytool.servermanager.types.request.PaginationRequest;
import mindustrytool.servermanager.types.response.LiveStats;
import mindustrytool.servermanager.types.response.ManagerMapDto;
import mindustrytool.servermanager.types.response.ManagerModDto;
import mindustrytool.servermanager.types.response.MapDto;
import mindustrytool.servermanager.types.response.MindustryPlayerDto;
import mindustrytool.servermanager.types.response.ModDto;
import mindustrytool.servermanager.types.response.PlayerInfoDto;
import mindustrytool.servermanager.types.response.ServerCommandDto;
import mindustrytool.servermanager.types.response.ServerWithStatsDto;
import mindustrytool.servermanager.types.response.ServerFileDto;
import mindustrytool.servermanager.types.response.StatsDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ServerController {

    private final ServerService serverService;
    private final GatewayService gatewayService;

    @GetMapping("/servers/{id}")
    public Mono<ServerWithStatsDto> getServer(@PathVariable("id") UUID serverId) {
        return serverService.getServer(serverId);
    }

    @GetMapping("/servers/{id}/players")
    public Flux<Player> getServerPlayers(@PathVariable("id") UUID serverId) {
        return serverService.getPlayers(serverId);
    }

    @GetMapping("/servers")
    public Flux<ServerWithStatsDto> getServers() {
        return serverService.getServers();
    }

    @GetMapping("/servers/{id}/files")
    Flux<ServerFileDto> getFiles(@PathVariable("id") UUID serverId, @RequestParam("path") String path) {
        return serverService.getFiles(serverId, URLDecoder.decode(path, StandardCharsets.UTF_8));
    }

    @GetMapping("/servers/{id}/files/exists")
    boolean fileExists(@PathVariable("id") UUID serverId, @RequestParam("path") String path) {
        return serverService.fileExists(serverId, URLDecoder.decode(path, StandardCharsets.UTF_8));
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

    @GetMapping("/servers/{id}/commands")
    public Flux<ServerCommandDto> getCommand(@PathVariable("id") UUID serverId) {
        return gatewayService.of(serverId).getServer().getCommands();
    }

    @PostMapping("/servers/{id}/commands")
    public Mono<Void> sendCommand(@PathVariable("id") UUID serverId, @RequestBody String command) {
        return gatewayService.of(serverId).getServer().sendCommand(command);
    }

    @PostMapping("/servers/{id}/say")
    public Mono<Void> say(@PathVariable("id") UUID serverId, @RequestBody String message) {
        return serverService.say(serverId, message);
    }

    @PostMapping(path = "/servers/{id}/host", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> host(@PathVariable("id") UUID serverId, @Validated @RequestBody HostServerRequest request) {
        return serverService.host(serverId, request);
    }

    @PostMapping(path = "/servers/{id}/host-from-server", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> hostFromServer(//
            @PathVariable("id") UUID serverId,
            @Validated @RequestBody HostFromSeverRequest request//
    ) {
        return serverService.hostFromServer(serverId, request).subscribeOn(Schedulers.single());
    }

    @PostMapping("/servers/{id}/set-player")
    public Mono<Void> setPlayer(@PathVariable("id") UUID serverId, @RequestBody MindustryPlayerDto request) {
        return serverService.setPlayer(serverId, request);
    }

    @GetMapping("/servers/{id}/stats")
    public Mono<StatsDto> stats(@PathVariable("id") UUID serverId) {
        return serverService.stats(serverId);
    }

    @GetMapping(path = "/servers/{id}/live-stats", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<LiveStats> liveStats(@PathVariable("id") UUID serverId) {
        return serverService.liveStats(serverId);
    }

    @PutMapping("/servers/{id}/shutdown")
    public Mono<Void> shutdown(@PathVariable("id") UUID serverId) {
        return serverService.shutdown(serverId);
    }

    @DeleteMapping("/servers/{id}/remove")
    public Mono<Void> remove(@PathVariable("id") UUID serverId) {
        return serverService.remove(serverId);
    }

    @PostMapping("/servers/{id}/pause")
    public Mono<Boolean> pause(@PathVariable("id") UUID serverId) {
        return serverService.pause(serverId);
    }

    @GetMapping(path = "/servers/{id}/image", produces = MediaType.IMAGE_PNG_VALUE)
    public Mono<byte[]> image(@PathVariable("id") UUID serverId) {
        return serverService.getImage(serverId);
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

    @GetMapping("servers/{id}/mods")
    public Flux<ModDto> getMods(@PathVariable("id") UUID serverId) {
        return serverService.getMods(serverId);
    }

    @GetMapping("servers/{id}/maps")
    public Flux<MapDto> getMaps(@PathVariable("id") UUID serverId) {
        return serverService.getMaps(serverId);
    }

    @GetMapping("servers/{id}/plugin-version")
    public Mono<String> getPluginVersion(@PathVariable("id") UUID serverId) {
        return gatewayService.of(serverId).getServer().getPluginVersion();
    }

    @GetMapping("servers/{id}/kicks")
    public Mono<Map<String, Long>> getKicks(@PathVariable("id") UUID serverId) {
        return gatewayService.of(serverId).getServer().getKickedIps();
    }

    @GetMapping("servers/{id}/player-infos")
    public Flux<PlayerInfoDto> getPlayerInfos(//
            @PathVariable("id") UUID serverId, //
            @Validated PaginationRequest request,
            @RequestParam(name = "banned", required = false) Boolean banned, //
            @RequestParam(name = "filter", required = false) String filter//
    ) {
        return gatewayService.of(serverId).getServer().getPlayers(request.getPage(), request.getSize(), banned, filter);
    }

    @GetMapping("servers/{id}/manager-version")
    public String getManagerVersion() {
        return Config.MANAGER_VERSION;
    }

    @GetMapping("servers/{id}/json")
    public Mono<JsonNode> getJson(@PathVariable("id") UUID serverId) {
        return gatewayService.of(serverId).getServer().getJson();
    }

    @GetMapping("servers/{id}/routes")
    public Mono<JsonNode> getRoutes(@PathVariable("id") UUID serverId) {
        return gatewayService.of(serverId).getServer().getRoutes();
    }

    @PostMapping("servers/{id}/mismatch")
    public Flux<String> getMismatch(//
            @PathVariable("id") UUID serverId,
            @Validated @RequestBody InitServerRequest init//
    ) {
        return serverService.getMismatch(serverId, init).flatMapMany(item -> Flux.fromIterable(item));
    }

    @GetMapping("mods")
    public Flux<ManagerModDto> getManagerMods() {
        return serverService.getManagerMods();
    }

    @GetMapping("maps")
    public Flux<ManagerMapDto> getManagerMaps() {
        return serverService.getManagerMaps();
    }

    @DeleteMapping("maps/{filename}")
    public void deleteManagerMap(@PathVariable("filename") String filename) {
        serverService.deleteManagerMap(filename);
    }

    @DeleteMapping("mods/{filename}")
    public void deleteManagerMod(@PathVariable("filename") String filename) {
        serverService.deleteManagerMod(filename);
    }

    @GetMapping(path = "/servers/{id}/workflow/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<JsonNode> getWorkflowEvents(@PathVariable("id") UUID serverId) {
        return gatewayService.of(serverId).getServer().getWorkflowEvents();
    }

    @GetMapping("servers/{id}/workflow/nodes")
    public Mono<JsonNode> getWorkflowNodes(@PathVariable("id") UUID serverId) {
        return gatewayService.of(serverId).getServer().getWorkflowNodes();
    }

    @PostMapping("servers/{id}/workflow/nodes/{nodeId}/emit")
    public Mono<JsonNode> getWorkflowNodes(@PathVariable("id") UUID serverId, @PathVariable("nodeId") String nodeId) {
        return gatewayService.of(serverId).getServer().getWorkflowNodes();
    }

    @GetMapping("servers/{id}/workflow/version")
    public Mono<Long> getWorkflowVersion(@PathVariable("id") UUID serverId) {
        return gatewayService.of(serverId).getServer().getWorkflowVersion();
    }

    @GetMapping("servers/{id}/workflow")
    public Mono<JsonNode> getWorkflow(@PathVariable("id") UUID serverId) {
        return gatewayService.of(serverId).getServer().getWorkflow();
    }

    @PostMapping("servers/{id}/workflow")
    public Mono<Void> saveWorkflow(@PathVariable("id") UUID serverId, @Validated @RequestBody JsonNode payload) {
        return gatewayService.of(serverId).getServer().saveWorkflow(payload);
    }

    @PostMapping("servers/{id}/workflow/load")
    public Mono<JsonNode> loadWorkflow(@PathVariable("id") UUID serverId, @Validated @RequestBody JsonNode payload) {
        return gatewayService.of(serverId).getServer().loadWorkflow(payload);
    }

}
