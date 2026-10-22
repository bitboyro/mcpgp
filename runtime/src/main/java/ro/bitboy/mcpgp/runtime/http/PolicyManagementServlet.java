package ro.bitboy.mcpgp.runtime.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ro.bitboy.mcpgp.runtime.PolicyManagement;
import ro.bitboy.mcpgp.runtime.Reloader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Separate administrator authentication and bounded JSON ingress for policy management. */
final class PolicyManagementServlet extends HttpServlet {
    private static final int LIMIT = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(16384).maxNumberLength(64).build())
            .build()).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private record Key(String name, byte[] digest) { }
    private final boolean enabled;
    private final List<Key> keys;
    private final PolicyManagement management;

    PolicyManagementServlet(RuntimeConfig config, Reloader reloader) {
        JsonNode options = config.document().at("/server/management");
        enabled = options.path("enabled").asBoolean(false);
        List<Key> configured = new ArrayList<>();
        options.path("keys").forEach(k -> configured.add(new Key(k.path("name").asText(), digest(k.path("value").asText()))));
        keys = List.copyOf(configured);
        management = new PolicyManagement(reloader);
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentType("application/json");
        if (!enabled) {
            response.setStatus(404);
            return;
        }
        String actor = null;
        String action = "authentication";
        try {
            actor = authenticate(request.getHeader("Authorization"));
            if (actor == null) {
                response.setHeader("WWW-Authenticate", "Bearer realm=\"mcpgp-management\"");
                throw new PolicyManagement.Failure(401, "MANAGEMENT_UNAUTHORIZED", "A separate administrator key is required");
            }
            if (request.getHeader("Origin") != null) {
                throw new PolicyManagement.Failure(403, "MANAGEMENT_ORIGIN_FORBIDDEN", "Browser-origin requests are not allowed");
            }
            String path = request.getPathInfo() == null ? "" : request.getPathInfo();
            if (request.getMethod().equals("GET") && (path.isEmpty() || path.equals("/"))) {
                action = "read";
                JSON.writeValue(response.getOutputStream(), management.read(actor));
                return;
            }
            if (!request.getMethod().equals("POST")) {
                response.setHeader("Allow", "GET, POST");
                throw new PolicyManagement.Failure(405, "MANAGEMENT_REQUEST_INVALID", "Unsupported management method");
            }
            if (!path.equals("/preview") && !path.equals("/apply")) {
                response.setStatus(404);
                return;
            }
            action = path.equals("/preview") ? "preview" : "apply";
            String contentType = request.getContentType();
            if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
                throw new PolicyManagement.Failure(415, "MANAGEMENT_CONTENT_TYPE", "Use application/json");
            }
            if (request.getContentLengthLong() > LIMIT) {
                throw tooLarge();
            }
            byte[] bytes = request.getInputStream().readNBytes(LIMIT + 1);
            if (bytes.length > LIMIT) {
                throw tooLarge();
            }
            JsonNode body;
            try {
                body = JSON.readTree(bytes);
            } catch (IOException e) {
                throw new PolicyManagement.Failure(400, "MANAGEMENT_REQUEST_INVALID", "Supply valid bounded JSON without duplicate keys");
            }
            Map<String, Object> result = path.equals("/preview") ? management.preview(body, actor) : management.apply(body, actor);
            JSON.writeValue(response.getOutputStream(), result);
        } catch (PolicyManagement.Failure e) {
            management.audit(action, actor, e.status() == 401 || e.status() == 403 ? "denied" : "error", e.code(), null, null, List.of());
            response.setStatus(e.status());
            JSON.writeValue(response.getOutputStream(), Map.of("code", e.code(), "message", e.getMessage()));
        } catch (RuntimeException e) {
            management.audit(action, actor, "error", "CONFIG_UPDATE_UNAVAILABLE", null, null, List.of());
            response.setStatus(503);
            JSON.writeValue(response.getOutputStream(), Map.of("code", "CONFIG_UPDATE_UNAVAILABLE", "message", "Policy management could not complete the request; inspect status before retrying"));
        }
    }

    private String authenticate(String header) {
        if (header == null || header.length() > 16384 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        byte[] candidate = digest(header.substring(7).strip());
        String found = null;
        for (Key key : keys) {
            if (MessageDigest.isEqual(candidate, key.digest())) {
                found = key.name();
            }
        }
        return found;
    }

    private static PolicyManagement.Failure tooLarge() {
        return new PolicyManagement.Failure(413, "MANAGEMENT_REQUEST_TOO_LARGE", "Management request exceeds 64 KiB");
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
