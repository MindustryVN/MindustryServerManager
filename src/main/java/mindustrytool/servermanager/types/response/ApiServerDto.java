package mindustrytool.servermanager.types.response;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ApiServerDto {
    private List<ServerDto> servers = new ArrayList<ServerDto>();
}
