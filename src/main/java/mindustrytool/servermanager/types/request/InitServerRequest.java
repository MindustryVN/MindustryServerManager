package mindustrytool.servermanager.types.request;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class InitServerRequest {

    private UUID id;

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

    @JsonProperty("isHub")
    private boolean isHub;

    @JsonProperty("isAutoTurnOff")
    private boolean isAutoTurnOff;

    private int port;

    private Map<String, String> env;

    private String image;
}
