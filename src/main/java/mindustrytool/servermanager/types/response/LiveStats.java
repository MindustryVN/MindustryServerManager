package mindustrytool.servermanager.types.response;

import java.time.Instant;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class LiveStats {
    private Long index;
    private StatsDto value;
    private Instant createdAt = Instant.now();

    public LiveStats(Long index, StatsDto value) {
        this.index = index;
        this.value = value;
    }
}
