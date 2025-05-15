package mindustrytool.servermanager.types.response;

import lombok.Data;
import lombok.experimental.Accessors;
import mindustrytool.servermanager.types.data.Team;

@Data
@Accessors(chain = true)
public class PlayerDto {
    private String uuid;
    private String ip;
    private String name;
    private Team team;
    private String locale;
}
