package mindustrytool.servermanager.messages.request;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StartServerMessageRequest {
    String mapName;
    String mode;
}
