package mindustrytool.servermanager.types.data;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class Player {
    private String uuid;
    private String name;
    private String ip;
    private Team team;
    private String locale;

    @JsonProperty("isAdmin")
    private boolean isAdmin;
}
