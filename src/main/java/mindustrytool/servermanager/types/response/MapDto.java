package mindustrytool.servermanager.types.response;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class MapDto {
    private String name;
    private String filename;
    private int width;
    private int height;
    private boolean isCustom;
}
