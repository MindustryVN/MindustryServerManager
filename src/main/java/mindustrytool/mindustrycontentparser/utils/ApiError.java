package mindustrytool.mindustrycontentparser.utils;


import org.springframework.http.HttpStatus;

import lombok.Data;
import lombok.EqualsAndHashCode;
import reactor.core.publisher.Mono;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
public class ApiError extends RuntimeException {

    @Serial
    private static final long serialVersionUID = -5611371618649386587L;

    public final HttpStatus status;

    public ApiError(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public ApiError(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public static <T> Mono<T> mono(HttpStatus status, String message) {
        return Mono.defer(() -> Mono.error(new ApiError(status, message)));
    }

    public static <T> Mono<T> badRequest(String message) {
        return mono(HttpStatus.BAD_REQUEST, message);
    }

    public static <T> Mono<T> unauthorized() {
        return mono(HttpStatus.UNAUTHORIZED, "You are unauthorized");
    }

    public static <T> Mono<T> forbidden() {
        return mono(HttpStatus.FORBIDDEN, "You have no permission to access this");
    }

    public static <T> Mono<T> forbidden(String message) {
        return mono(HttpStatus.FORBIDDEN, message);
    }

    public static <T> Mono<T> conflict(String message) {
        return mono(HttpStatus.CONFLICT, message);
    }

    public static <T> Mono<T> internal() {
        return mono(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
    }

    public static <T, E extends ApiError> Mono<T> mono(E exception) {
        return Mono.defer(() -> Mono.error(exception));
    }

    public static <T> Mono<T> notFound(Object id, String contentType) {
        return mono(HttpStatus.NOT_FOUND, String.format("Data not found (%s:%s)", contentType, id.toString()));
    }

    public static <T> Mono<T> notFound(Object id, Class<?> contentType) {
        return notFound(id, contentType.getSimpleName());
    }
}
