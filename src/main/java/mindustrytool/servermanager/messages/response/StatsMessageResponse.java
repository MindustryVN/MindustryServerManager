package mindustrytool.servermanager.messages.response;

import java.util.List;

import lombok.Data;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@Data
public class StatsMessageResponse {
    public long ramUsage = 0;
    public long totalRam = 0;
    public long players = 0;
    public String mapName = "";
    public byte[] mapData;
    public List<String> mods;
    public String status;
}
