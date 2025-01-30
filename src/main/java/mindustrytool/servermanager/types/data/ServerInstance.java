package mindustrytool.servermanager.types.data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.experimental.Accessors;
import mindustrytool.servermanager.EnvConfig;

@Data
@Accessors(chain = true)
public class ServerInstance {
    private final UUID id;
    private final UUID userId;
    private final String name;
    private final String description;
    private final String mode;
    private final String containerId;
    private final int port;
    private final boolean isAutoTurnOff;

    private String status;

    @JsonIgnore
    private boolean killFlag = false;

    @JsonIgnore
    private final List<Player> players = new ArrayList<>();

    @JsonIgnore
    private final EnvConfig envConfig;

    @JsonIgnore
    private final Instant initiatedAt = Instant.now();
}
