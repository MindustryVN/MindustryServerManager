package mindustrytool.servermanager.messages.response;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@Data
public class StatsMessageResponse {
    public long ramUsage = 0;
    public long totalRam = 0;
    public long cpuUsage = 0l;
    public long players = 0;
    public String mapName = "";
    public byte[] mapData;
    public List<String> mods = new ArrayList<>();
    public String status = "UNSET";
}
