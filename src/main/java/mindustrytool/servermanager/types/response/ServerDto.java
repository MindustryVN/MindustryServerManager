package mindustrytool.servermanager.types.response;

import java.util.List;
import java.util.UUID;

import lombok.Data;

@Data
public class ServerDto {
    public UUID id;
    public UUID userId;
    public String name;
    public String description;
    public String mode;
    public int port;
    public String hostCommand;
    public String status = "BACKEND_UNSET";
    public boolean official;
    public float ramUsage;
    public float totalRam;
    public long players;
    public String mapName;
    private List<String> mods;
}
