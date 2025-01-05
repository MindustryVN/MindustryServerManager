package mindustrytool.servermanager.types.request;

import java.util.UUID;

import org.hibernate.validator.constraints.Length;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateServerRequest {

    @org.hibernate.validator.constraints.UUID
    @NotEmpty
    @NotNull
    private UUID id;

    @org.hibernate.validator.constraints.UUID
    @NotEmpty
    @NotNull
    private UUID userId;

    @NotEmpty
    @NotNull
    @Length(max = 128)
    private String name;

    @NotEmpty
    @NotNull
    @Length(max = 2048)
    private String description;

    @NotEmpty
    @NotNull
    @Length(max = 256)
    private String mode;

    @Length(max = 256)
    private String hostCommand;

    private int port;
}
