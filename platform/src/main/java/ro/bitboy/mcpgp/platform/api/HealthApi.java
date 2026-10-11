package ro.bitboy.mcpgp.platform.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mcpgp.platform.store.Store;

import java.util.Map;

/**
 * {@code GET /healthz} (spec/platform-api.md §7.2), public for container health checks and load
 * balancers: 200 when the platform can reach its store, 503 otherwise. It says nothing else.
 */
@RestController
public class HealthApi {

    private static final Logger LOG = LoggerFactory.getLogger(HealthApi.class);

    private final Store store;

    public HealthApi(Store store) {
        this.store = store;
    }

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, String>> health() {
        try {
            store.check();
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(Map.of("status", "up"));
        } catch (RuntimeException e) {
            LOG.warn("health check: the store can't be reached: {}", e.getMessage());
            return ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON).body(Map.of("status", "down"));
        }
    }
}
