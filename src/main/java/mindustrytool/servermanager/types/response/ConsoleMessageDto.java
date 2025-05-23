package mindustrytool.servermanager.types.response;

import java.time.Instant;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ConsoleMessageDto {
    private String message;
    private Instant timestamp;
}
