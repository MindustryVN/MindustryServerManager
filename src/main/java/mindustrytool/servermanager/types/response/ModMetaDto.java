package mindustrytool.servermanager.types.response;

import java.util.List;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ModMetaDto {
    private String name;
    private String internalName;
    private String minGameVersion = "0";
    private String displayName, author, description, subtitle, version, main, repo;
    private List<String> dependencies = List.of();
    private boolean hidden;
    private boolean java;
}
