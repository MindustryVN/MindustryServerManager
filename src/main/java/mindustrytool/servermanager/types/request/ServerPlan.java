package mindustrytool.servermanager.types.request;

import lombok.Data;

@Data
public class ServerPlan {
    private int id = 0;
    private String name = "";
    private long ram = 0;
    private float cpu = 0;
}
