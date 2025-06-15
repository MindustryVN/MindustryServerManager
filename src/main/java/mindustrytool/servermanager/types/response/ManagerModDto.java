package mindustrytool.servermanager.types.response;

import java.util.List;
import java.util.UUID;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ManagerModDto {
    private String name;
    private String filename;
    private ModMetaDto meta;
    private List<UUID> servers;
}
