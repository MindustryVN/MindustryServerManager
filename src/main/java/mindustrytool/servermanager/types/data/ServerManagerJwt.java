package mindustrytool.servermanager.types.data;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.UUID;

@Data
@Accessors(chain = true)
public class ServerManagerJwt {
    UUID id;
    UUID userId;
}
