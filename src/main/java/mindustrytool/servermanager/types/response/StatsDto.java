package mindustrytool.servermanager.types.response;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@Data
public class StatsDto {
    private float ramUsage = 0;
    private float totalRam = 0;
    private float cpuUsage = 0l;
    private long players = 0;
    private String mapName = "";
    private byte[] mapData;
    private List<String> mods = new ArrayList<>();
    private int kicks;
    private boolean isPaused = false;
    private String status = "UNSET";
}
