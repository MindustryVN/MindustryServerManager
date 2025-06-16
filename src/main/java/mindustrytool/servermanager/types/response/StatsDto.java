package mindustrytool.servermanager.types.response;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@Data
public class StatsDto {
    private long tps;
    private float ramUsage = 0;
    private float jvmRamUsage = -1;
    private float totalRam = 0;
    private float cpuUsage = 0l;
    private long players = 0;
    private String mapName = "";
    private List<ModDto> mods = new ArrayList<>();
    private int kicks;
    private boolean isPaused = false;
    private boolean isHosting = false;
    private String status = "MANAGER_STATS_UNSET";
    private String version = "UNSET";
}
