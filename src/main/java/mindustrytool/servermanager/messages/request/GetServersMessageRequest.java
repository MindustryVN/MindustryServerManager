package mindustrytool.servermanager.messages.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class GetServersMessageRequest {
    @Min(0)
    private int page;

    @Min(1)
    @Max(100)
    private int size;
}
