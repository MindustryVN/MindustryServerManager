package mindustrytool.servermanager.types.request;

import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class HostServerRequest {

    @NotEmpty
    private UUID id;

    @NotEmpty
    @Size(max = 256)
    private String mode;

    @NotEmpty
    @Size(max = 256)
    private String mapName;

    @Size(max = 256)
    private String hostCommand;
}
