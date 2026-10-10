package ro.bitboy.mcpgp.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** The API's own OpenAPI document (spec/platform-api.md §4), public: it holds no data. */
@RestController
public class OpenApiApi {

    public static final String RESOURCE = "/mcpgp/platform-api.openapi.yaml";

    private final byte[] json;

    public OpenApiApi() {
        try (InputStream in = OpenApiApi.class.getResourceAsStream(RESOURCE)) {
            json = Json.MAPPER.writeValueAsBytes(new ObjectMapper(new YAMLFactory()).readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/api/v1/openapi.json")
    public ResponseEntity<byte[]> openapi() {
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(json);
    }
}
