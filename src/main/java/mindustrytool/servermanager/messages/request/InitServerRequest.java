package mindustrytool.servermanager.messages.request;

import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class InitServerRequest {

    @NotEmpty
    private UUID id;

    @NotEmpty
    private UUID userId;

    @NotEmpty
    @Size(max = 128)
    private String name;

    @NotEmpty
    @Size(max = 2048)
    private String description;

    @NotEmpty
    @Size(max = 256)
    private String mode;

    @Size(max = 256)
    private String hostCommand;

    private int port;
}
