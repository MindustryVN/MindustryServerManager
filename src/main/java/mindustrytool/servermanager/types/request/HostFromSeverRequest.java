package mindustrytool.servermanager.types.request;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class HostFromSeverRequest {
    private final InitServerRequest init;
    private final HostServerRequest host;
}
