package mindustrytool.servermanager.messages.response;

import java.util.UUID;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class GetServersMessageResponse {

    private List<ResponseData> servers;

    @Data
    @Accessors(chain = true)
    public static class ResponseData {
        private UUID id;
        private String name;
        private String description;
        private String mode;
        private int port;
        private long players;
        private String mapName;
        private List<String> mods;
    }
}
