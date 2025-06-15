package mindustrytool.servermanager.types.response;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class CommandParamDto {
    public String name;
    public boolean optional;
    public boolean variadic;
}
