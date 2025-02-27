package mindustrytool.servermanager.types.request;

import org.springframework.http.codec.multipart.FilePart;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AddServerFileRequest {
    @NotNull
    private FilePart file;
}
