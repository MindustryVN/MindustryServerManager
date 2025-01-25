package mindustrytool.servermanager.types.data;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class Team {
    private String name;
    private String color;
}
