package mindustrytool.servermanager.types.request;

import lombok.Data;
import lombok.experimental.Accessors;
import mindustrytool.servermanager.types.data.Player;

@Data
@Accessors(chain = true)
public class BuildLog {
    private String message;
    private Player player;
    private BuildingDto building;

    @Data
    @Accessors(chain = true)
    public static class BuildingDto {
        private float x;
        private float y;
        private String name;
        private String lastAccess;
    }
}
