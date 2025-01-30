package mindustrytool.servermanager.types.request;

import lombok.Data;
import lombok.experimental.Accessors;
import mindustrytool.servermanager.messages.request.StartServerMessageRequest;

@Data
@Accessors(chain = true)
public class HostFromSeverRequest {
    private final InitServerRequest init;
    private final StartServerMessageRequest host;
}
