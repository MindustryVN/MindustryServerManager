package mindustrytool.servermanager.types.response;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@Data
public class StatsDto {
    public float ramUsage = 0;
    public float totalRam = 0;
    public float cpuUsage = 0l;
    public long players = 0;
    public String mapName = "";
    public byte[] mapData;
    public List<String> mods = new ArrayList<>();
    public int kicks;
    public String status = "UNSET";
}
