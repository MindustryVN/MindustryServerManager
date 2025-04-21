package mindustrytool.servermanager.types.data;

import lombok.Data;
import lombok.experimental.Accessors;
import mindustrytool.servermanager.types.request.HostServerRequest;
import mindustrytool.servermanager.types.request.InitServerRequest;

@Data
@Accessors(chain = true)
public class ServerContainerMetadata {

    private String serverManagerImageHash;
    private String serverImageHash;

    private InitServerRequest init;
    private HostServerRequest host;
}
