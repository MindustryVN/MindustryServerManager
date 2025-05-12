package mindustrytool.servermanager.messages.response;

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

    @Data
    @Accessors(chain = true)
    public static class CommandParamDto {
        public String name;
        public boolean optional;
        public boolean variadic;
    }
}
