package mindustrytool.servermanager.types.data;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class Player {
    private static final int MAX_SESSION_TIME = 5; // minutes

    private String uuid;
    private String name;
    private String ip;
    private UUID userId;
    private Team team;

    private Instant leaveAt;
    private Instant createdAt = Instant.now();

    @JsonIgnore
    public long calculateExp() {
        var minutes = Duration.between(createdAt, Instant.now()).toMinutes();

        return minutes;
    }

    @JsonIgnore
    public boolean isSessionStale() {
        return leaveAt != null && Duration.between(leaveAt, Instant.now()).getSeconds() / 60 > MAX_SESSION_TIME;
    }
}
