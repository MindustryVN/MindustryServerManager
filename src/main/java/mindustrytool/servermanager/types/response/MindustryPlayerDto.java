package mindustrytool.servermanager.types.response;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class MindustryPlayerDto {
    String uuid;
    boolean admin;
    String name;
    String loginLink;
    long exp;
}
