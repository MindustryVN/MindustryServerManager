package mindustrytool.servermanager.types.response;

import java.time.Instant;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class LiveStats {
    private Long index;
    private Instant createdAt = Instant.now();
    private StatsDto value;
}
