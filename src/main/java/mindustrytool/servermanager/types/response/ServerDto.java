package mindustrytool.servermanager.types.response;

import java.util.UUID;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ServerDto {
    private UUID id;
    private UUID userId;
    private String name;
    private String description;
    private String mode;

    private int port;
}
