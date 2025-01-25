package mindustrytool.servermanager.utils;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import reactor.core.publisher.Mono;

public class Utils {

    public static final ObjectMapper objectMapper = new ObjectMapper()//
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)//
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)//
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)//
            .findAndRegisterModules();

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

    public static String toJsonString(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Can not parse to json: " + e.getMessage(), e);
        }
    }

    public static <T> T readJsonAsClass(String data, Class<T> clazz) {
        try {
            return objectMapper.readValue(data, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Can not parse to json: " + e.getMessage(), e);
        }
    }
}
