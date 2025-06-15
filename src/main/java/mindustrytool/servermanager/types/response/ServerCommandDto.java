package mindustrytool.servermanager.types.response;

import java.util.List;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ServerCommandDto {
    public String text;
    public String paramText;
    public String description;
    public List<CommandParamDto> params;
}
