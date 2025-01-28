package mindustrytool.servermanager.messages.response;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;
import mindustrytool.servermanager.types.response.ApiServerDto;

@Data
@Accessors(chain = true)
public class GetServersMessageResponse {

    private List<ApiServerDto> servers = new ArrayList<ApiServerDto>();
}
