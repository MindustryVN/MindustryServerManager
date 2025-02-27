package mindustrytool.servermanager.types.response;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(fluent = true, chain = true)
public class ServerFileDto {
    public String name;
    public boolean directory;
    public String data;
    public long size;
}
