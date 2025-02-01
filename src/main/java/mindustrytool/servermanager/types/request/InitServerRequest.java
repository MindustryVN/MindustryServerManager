package mindustrytool.servermanager.types.request;

import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class InitServerRequest {

    @NotNull
    private UUID id;

    @NotNull
    private UUID userId;

    @NotEmpty
    @NotNull
    @Size(max = 128)
    private String name;

    @NotEmpty
    @NotNull
    @Size(max = 2048)
    private String description;

    @NotEmpty
    @NotNull
    @Size(max = 256)
    private String mode;

    @Size(max = 256)
    private String hostCommand;

    private boolean isAutoTurnOff = true;
    private boolean isHub = false;

    private int port;
}
