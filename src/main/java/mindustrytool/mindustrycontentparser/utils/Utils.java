package mindustrytool.mindustrycontentparser.utils;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;

import arc.util.UnsafeRunnable;
import arc.util.serialization.Base64Coder;
import reactor.core.publisher.Mono;

public class Utils {

    public static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(10);

    public static void runIgnoreError(UnsafeRunnable r) {
        try {
            r.run();
        } catch (Throwable ignore) {
        }
    }

    public static byte[] encode(byte[] data) {
        return new String(Base64Coder.encode(data)).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] decode(byte[] data) {
        return Base64Coder.decode(new String(data, StandardCharsets.UTF_8));
    }

    public static String imageToBase64(BufferedImage image) {
        return Base64Coder.encodeLines(Utils.toByteArray(image));
    }

    public static synchronized byte[] toByteArray(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "webp", baos);
            return baos.toByteArray();

        } catch (IOException e) {
            throw new ApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to write image to bytes");
        }
    }

    public static Mono<byte[]> readAllBytes(FilePart file) {
        return DataBufferUtils.join(file.content()).handle((buffer, sink) -> {
            try {
                sink.next(buffer.asInputStream().readAllBytes());
            } catch (Exception e) {
                sink.error(new ApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read file", e));
            }
        });
    }

}
