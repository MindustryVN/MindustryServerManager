package mindustrytool.mindustrycontentparser.types.response;

import java.io.Serializable;
import java.util.Date;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class ErrorResponse implements Serializable {
    /**
     *
     */
    private static final long serialVersionUID = 1724311534411892163L;
    private final int status;
    private final String message;
    private final String url;

    private final Date createdAt = new Date();
}
