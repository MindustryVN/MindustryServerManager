package mindustrytool.servermanager.types.request;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class HostServerRequest {

    @Size(max = 256)
    private String mode;

    @Size(max = 256)
    private String mapName;

    @Size(max = 256)
    private String hostCommand;
}
