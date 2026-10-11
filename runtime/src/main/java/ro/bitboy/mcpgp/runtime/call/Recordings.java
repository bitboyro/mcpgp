package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The recording pack (packaging.md §4.1): named, dated sessions capturing the upstream requests and
 * responses of one MCP session, bodies included, **redacted before they are stored**: secret values,
 * fields named in the definition's {@code redaction.fields} (at any depth in JSON bodies), and
 * credential headers. Bodies are capped at the response limit. Stored under
 * {@code <storage.root>/recordings/<id>/} ({@code session.json}, {@code calls.jsonl}), owner-only;
 * recordings older than the retention period are removed when the server starts.
 */
public final class Recordings {

    /** The MCP session the current tool call belongs to (set around each call). */
    public static final ThreadLocal<String> SESSION = new ThreadLocal<>();

    private static final Logger LOG = LoggerFactory.getLogger(Recordings.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<Path, Recordings> SHARED = new ConcurrentHashMap<>();
    private static final Set<String> CREDENTIAL_HEADERS = Set.of("authorization", "proxy-authorization", "cookie", "set-cookie");
    private static final DateTimeFormatter ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    static final int MAX_CALLS = 1_000;

    private final Path root;
    private final Set<String> fields;
    private final Set<String> dropHeaders;
    private final Redactor redactor;
    private final int bodyLimit;
    private final Clock clock;
    /** MCP session → its running recording's id. */
    private final Map<String, String> running = new ConcurrentHashMap<>();
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();

    Recordings(Path root, Set<String> fields, Set<String> apiKeyHeaders, Set<String> secrets, int bodyLimitBytes, int retentionDays, Clock clock) {
        this.root = root;
        this.fields = lower(fields);
        this.dropHeaders = new java.util.HashSet<>(CREDENTIAL_HEADERS);
        this.dropHeaders.addAll(lower(apiKeyHeaders));
        this.redactor = new Redactor(secrets);
        this.bodyLimit = bodyLimitBytes;
        this.clock = clock;
        removeOlderThan(Duration.ofDays(retentionDays));
    }

    /**
     * The recordings under a storage root, shared by the API tool handler (which captures) and the pack
     * tools (which start, stop and read).
     */
    public static Recordings of(Path storageRoot, Set<String> fields, Set<String> apiKeyHeaders, Set<String> secrets, int bodyLimitBytes,
                                int retentionDays) {
        return SHARED.computeIfAbsent(storageRoot.resolve("recordings").toAbsolutePath().normalize(),
                dir -> new Recordings(dir, fields, apiKeyHeaders, secrets, bodyLimitBytes, retentionDays, Clock.systemUTC()));
    }

    // ---------------------------------------------------------------------- pack tools

    public Map<String, Object> start(String session, String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description: what is being recorded and why");
        }
        String key = key(session);
        if (running.containsKey(key)) {
            throw new IllegalStateException("A recording is running in this session (" + running.get(key) + "); stop it first");
        }
        Instant now = clock.instant();
        String id = "r-" + ID.format(now) + "-" + HexFormat.of().formatHex(randomBytes());
        ObjectNode meta = JSON.createObjectNode().put("id", id).put("description", redactor.redact(description)).put("started", now.toString());
        meta.putNull("stopped");
        meta.putNull("findings");
        meta.put("calls", 0);
        write(id, meta);
        running.put(key, id);
        counts.put(id, 0);
        return JSON.convertValue(meta, Map.class);
    }

    public Map<String, Object> stop(String session, String findings) {
        String id = running.remove(key(session));
        if (id == null) {
            throw new IllegalStateException("No recording is running in this session; start one with recording_start");
        }
        ObjectNode meta = read(id).orElseThrow();
        meta.put("stopped", clock.instant().toString());
        if (findings != null && !findings.isBlank()) {
            meta.put("findings", redactor.redact(findings));
        }
        meta.put("calls", counts.getOrDefault(id, 0));
        write(id, meta);
        counts.remove(id);
        return JSON.convertValue(meta, Map.class);
    }

    /** Recording sessions, newest first. */
    public List<Map<String, Object>> sessions() {
        List<Map<String, Object>> sessions = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return sessions;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).sorted(Comparator.reverseOrder())
                    .forEach(dir -> read(dir.getFileName().toString()).ifPresent(meta -> sessions.add(JSON.convertValue(meta, Map.class))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sessions;
    }

    /** The calls of a recording (this session's running one when {@code id} is null), in order. */
    public List<Map<String, Object>> calls(String session, String id) {
        String recording = id != null ? id : running.get(key(session));
        if (recording == null) {
            throw new IllegalStateException("No recording is running in this session; name one from recording_sessions");
        }
        if (!recording.matches("r-[0-9]{8}-[0-9]{6}-[0-9a-f]+") || read(recording).isEmpty()) {
            throw new IllegalArgumentException("No recording " + recording);
        }
        List<Map<String, Object>> calls = new ArrayList<>();
        Path file = root.resolve(recording).resolve("calls.jsonl");
        if (Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file)) {
                    calls.add(JSON.readValue(line, Map.class));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return calls;
    }

    // ---------------------------------------------------------------------- capture

    /** One upstream exchange of the current session, when it is recording; {@code response} is null when there was no answer. */
    void capture(String tool, RuntimeConfig_Instance instance, RequestBuilder.UpstreamRequest request, HttpResponse<byte[]> response, String error) {
        String id = running.get(key(SESSION.get()));
        if (id == null) {
            return;
        }
        int count = counts.merge(id, 1, Integer::sum);
        if (count > MAX_CALLS) {
            if (count == MAX_CALLS + 1) {
                LOG.warn("Recording {} reached {} calls; later calls are not recorded", id, MAX_CALLS);
            }
            return;
        }
        ObjectNode call = JSON.createObjectNode().put("ts", clock.instant().toString());
        if (tool != null) {
            call.put("tool", tool);
        }
        call.put("api", instance.api()).put("instance", instance.id());
        ObjectNode req = call.putObject("request").put("method", request.method())
                .put("url", redactor.redact(maskQuery(request.uri().toString())));
        req.set("headers", headers(request.headers()));
        req.set("body", body(request.body(), request.contentType()));
        if (response != null) {
            ObjectNode res = call.putObject("response").put("status", response.statusCode());
            List<Map.Entry<String, String>> headers = new ArrayList<>();
            response.headers().map().forEach((name, values) -> values.forEach(v -> headers.add(Map.entry(name, v))));
            res.set("headers", headers(headers));
            res.set("body", body(response.body(), response.headers().firstValue("Content-Type").orElse(null)));
        } else {
            call.put("error", redactor.redact(error == null ? "no answer" : error));
        }
        try {
            Files.writeString(root.resolve(id).resolve("calls.jsonl"), JSON.writeValueAsString(call) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("Recording {}: a call couldn't be stored: {}", id, e.getMessage());
        }
    }

    /** What capture needs of an instance (kept apart from RuntimeConfig for tests). */
    record RuntimeConfig_Instance(String api, String id) {
    }

    private ObjectNode headers(List<Map.Entry<String, String>> headers) {
        ObjectNode out = JSON.createObjectNode();
        for (Map.Entry<String, String> header : headers) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (dropHeaders.contains(name)) {
                continue;
            }
            out.put(name, fields.contains(name) ? "***" : redactor.redact(header.getValue()));
        }
        return out;
    }

    private JsonNode body(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            return JSON.nullNode();
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        boolean text = type.contains("json") || type.startsWith("text/") || type.contains("xml") || type.contains("x-www-form-urlencoded");
        if (!text) {
            return JSON.createObjectNode().put("binary", true).put("contentType", contentType).put("sizeBytes", bytes.length)
                    .put("sha256", sha256(bytes));
        }
        if (type.contains("json")) {
            try {
                JsonNode json = JSON.readTree(bytes);
                JsonNode masked = mask(json);
                String rendered = redactor.redact(JSON.writeValueAsString(masked));
                if (rendered.length() > bodyLimit) {
                    return truncated(rendered, bytes.length);
                }
                return JSON.readTree(rendered);
            } catch (IOException e) {
                // not valid JSON after all: treated as text
            }
        }
        String value = redactor.redact(type.contains("x-www-form-urlencoded") ? maskQuery("?" + new String(bytes, StandardCharsets.UTF_8)).substring(1)
                : new String(bytes, StandardCharsets.UTF_8));
        return value.length() > bodyLimit ? truncated(value, bytes.length) : TextNode.valueOf(value);
    }

    private JsonNode truncated(String text, int size) {
        return JSON.createObjectNode().put("truncated", true).put("sizeBytes", size).put("text", text.substring(0, bodyLimit));
    }

    /** JSON with the values of redaction fields replaced, at any depth. */
    private JsonNode mask(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = JSON.createObjectNode();
            node.properties().forEach(e -> copy.set(e.getKey(), fields.contains(e.getKey().toLowerCase(Locale.ROOT)) ? TextNode.valueOf("***") : mask(e.getValue())));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JSON.createArrayNode();
            node.forEach(item -> copy.add(mask(item)));
            return copy;
        }
        return node;
    }

    /** Query parameters (and form fields) named in the redaction fields: their values replaced. */
    private String maskQuery(String url) {
        int q = url.indexOf('?');
        if (q < 0) {
            return url;
        }
        StringBuilder out = new StringBuilder(url.substring(0, q + 1));
        String[] pairs = url.substring(q + 1).split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            String name = java.net.URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            out.append(eq >= 0 && fields.contains(name) ? pair.substring(0, eq + 1) + "***" : pair);
            if (i < pairs.length - 1) {
                out.append('&');
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------- storage

    private void write(String id, ObjectNode meta) {
        Path dir = root.resolve(id);
        try {
            Files.createDirectories(dir);
            ownerOnly(root);
            ownerOnly(dir);
            Files.writeString(dir.resolve("session.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(meta));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<ObjectNode> read(String id) {
        Path file = root.resolve(id).resolve("session.json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            ObjectNode meta = (ObjectNode) JSON.readTree(file.toFile());
            Integer live = counts.get(id);
            if (live != null) {
                meta.put("calls", Math.min(live, MAX_CALLS));
            }
            return Optional.of(meta);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void removeOlderThan(Duration retention) {
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant cutoff = clock.instant().minus(retention);
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Optional<ObjectNode> meta = read(dir.getFileName().toString());
                Instant started = meta.map(m -> Instant.parse(m.path("started").asText("1970-01-01T00:00:00Z"))).orElse(Instant.EPOCH);
                if (started.isBefore(cutoff)) {
                    try (Stream<Path> files = Files.walk(dir)) {
                        files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                    }
                    LOG.info("Recording {} is older than {} days; removed", dir.getFileName(), retention.toDays());
                }
            }
        } catch (IOException e) {
            LOG.warn("Old recordings couldn't be removed: {}", e.getMessage());
        }
    }

    private static void ownerOnly(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException e) {
            // not a POSIX file system: the directory's own permissions apply
        }
    }

    private static Set<String> lower(Set<String> values) {
        Set<String> out = new java.util.HashSet<>();
        values.forEach(v -> out.add(v.toLowerCase(Locale.ROOT)));
        return out;
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[3];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String key(String session) {
        return session == null ? "" : session;
    }
}
