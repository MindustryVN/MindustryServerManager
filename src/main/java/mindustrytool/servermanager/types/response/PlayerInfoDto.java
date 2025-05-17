package mindustrytool.servermanager.types.response;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PlayerInfoDto {
    public String id;
    public String lastName = "<unknown>", lastIP = "<unknown>";
    public List<String> ips = new ArrayList<>();
    public List<String> names = new ArrayList<>();
    public String adminUsid;
    public int timesKicked;
    public int timesJoined;
    public boolean banned, admin;
    public long lastKicked;
}
