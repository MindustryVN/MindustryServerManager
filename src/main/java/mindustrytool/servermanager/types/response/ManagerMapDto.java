package mindustrytool.servermanager.types.response;

import java.util.List;
import java.util.UUID;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ManagerMapDto {
    private String name;
    private String filename;
    private int width;
    private int height;
    private boolean isCustom;
    private List<UUID> servers;
}
