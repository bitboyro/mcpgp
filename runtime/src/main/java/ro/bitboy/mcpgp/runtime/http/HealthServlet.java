package ro.bitboy.mcpgp.runtime.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ro.bitboy.mcpgp.runtime.config.BaseUrl;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /health/live} and {@code /health/ready} (runtime-config §4.3). Ready means the config is loaded
 * and at least one instance accepts a TCP connection; results are cached briefly so probes stay cheap.
 */
final class HealthServlet extends HttpServlet {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CACHE = Duration.ofSeconds(10);
    private static final int CONNECT_TIMEOUT_MS = 2000;

    private final List<RuntimeConfig.Instance> instances;
    private final Clock clock;
    private Map<String, String> last;
    private Instant checkedAt = Instant.EPOCH;

    HealthServlet(List<RuntimeConfig.Instance> instances, Clock clock) {
        this.instances = instances;
        this.clock = clock;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo() == null ? "" : request.getPathInfo();
        response.setContentType("application/json");
        switch (path) {
            case "/live" -> JSON.writeValue(response.getOutputStream(), Map.of("status", "up"));
            case "/ready" -> {
                Map<String, String> states = states();
                long up = states.values().stream().filter("up"::equals).count();
                String status = up == states.size() ? "up" : up > 0 ? "degraded" : "down";
                response.setStatus(up > 0 ? 200 : 503);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", status);
                body.put("instances", states);
                JSON.writeValue(response.getOutputStream(), body);
            }
            default -> response.setStatus(404);
        }
    }

    private synchronized Map<String, String> states() {
        Instant now = clock.instant();
        if (last != null && now.isBefore(checkedAt.plus(CACHE))) {
            return last;
        }
        Map<String, String> states = new LinkedHashMap<>();
        for (RuntimeConfig.Instance instance : instances) {
            URI uri = BaseUrl.resolve(instance.baseUrl(), "");
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), CONNECT_TIMEOUT_MS);
                states.put(instance.id(), "up");
            } catch (IOException e) {
                states.put(instance.id(), "down");
            }
        }
        last = states;
        checkedAt = now;
        return states;
    }
}
