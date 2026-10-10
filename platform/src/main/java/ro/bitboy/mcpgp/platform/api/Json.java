package ro.bitboy.mcpgp.platform.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;

/**
 * JSON for the API, with the same Jackson as the compiler (manifests pass through unchanged). Responses
 * are written here rather than by Spring's converters.
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {
    }

    public static ResponseEntity<byte[]> ok(Object body) {
        return status(200, body);
    }

    public static ResponseEntity<byte[]> status(int status, Object body) {
        try {
            byte[] bytes = body instanceof String text ? text.getBytes(java.nio.charset.StandardCharsets.UTF_8) : MAPPER.writeValueAsBytes(body);
            return ResponseEntity.status(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(bytes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw ApiException.validation("the body is not valid JSON");
        }
    }
}
