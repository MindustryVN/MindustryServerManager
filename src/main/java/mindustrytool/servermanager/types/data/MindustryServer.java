package mindustrytool.servermanager.types.data;

import lombok.Getter;

public class MindustryServer {

    @Getter
    private final int port;


    public MindustryServer(int port) {
        this.port = port;
    }
}
