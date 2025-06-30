package mindustrytool.servermanager.types.response;

import java.util.List;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ModMetaDto {
    private String name = "Unknown";
    private String internalName = "Unknown";
    private String minGameVersion = "0";
    private String displayName = "Unknown", author = "Unknown", description = "Unknown", subtitle = "Unknown",
            version = "Unknown", main = "Unknown", repo = "Unknown";
    private List<String> dependencies = List.of();
    private boolean hidden = false;
    private boolean java = false;
}
