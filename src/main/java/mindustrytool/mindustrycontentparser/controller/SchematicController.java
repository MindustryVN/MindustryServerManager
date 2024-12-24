package mindustrytool.mindustrycontentparser.controller;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import mindustrytool.mindustrycontentparser.service.SchematicService;
import mindustrytool.mindustrycontentparser.types.request.SchematicPreviewRequest;
import mindustrytool.mindustrycontentparser.types.response.SchematicPreviewResult;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/schematics")
@RequiredArgsConstructor
public class SchematicController {

    private final SchematicService schematicService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<SchematicPreviewResult> getPreview(@Validated @ModelAttribute SchematicPreviewRequest request) {
        return schematicService.getPreview(request);
    }
}
