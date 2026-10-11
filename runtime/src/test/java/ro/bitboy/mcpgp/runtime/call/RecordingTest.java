package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.mcp.McpServerFactory;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The recording pack (packaging.md §4.1): a session's upstream calls, redacted before they are stored. */
class RecordingTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Manifest ORDERS = Manifest.file(REPO.resolve("examples/manifest/orders-mcp.manifest.json"));
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PASSWORD = "basic-pass-9f2c";

    @TempDir
    Path dir;
    MockTarget target;

    @AfterEach
    void stop() {
        if (target != null) {
            target.close();
        }
    }

    McpServerFactory server() throws Exception {
        target = new MockTarget();
        Path file = dir.resolve("orders-mcp.yaml");
        Files.writeString(file, """
                instances:
                  - { id: prod-eu, baseUrl: "%s", auth: { type: basic, username: svc, password: "${env:P}" } }
                """.formatted(target.address()));
        RuntimeConfig config = new ConfigLoader(new Schemas()).load(ORDERS, new ConfigLoader.Options(Optional.of(file), dir, Map.of("P", PASSWORD),
                Map.of("storage.root", dir.resolve("data").toString())));
        return new McpServerFactory(ORDERS, config);
    }

    static JsonNode call(McpServerFactory server, String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = server.call(null, new McpSchema.CallToolRequest(tool, arguments, null));
        assertThat(result.isError()).as("%s: %s", tool, result.content()).isFalse();
        return JSON.valueToTree(result.structuredContent());
    }

    static boolean fails(McpServerFactory server, String tool, Map<String, Object> arguments) {
        return Boolean.TRUE.equals(server.call(null, new McpSchema.CallToolRequest(tool, arguments, null)).isError());
    }

    @Test
    void recordsTheSessionsUpstreamCallsRedacted() throws Exception {
        McpServerFactory server = server();
        assertThat(server.catalog().tool("orders_recording_start")).as("offered now that the runtime implements it").isPresent();

        // not recording: nothing is kept
        target.respond(MockTarget.Response.json(200, "{\"id\":\"c-0\"}"));
        call(server, "orders_customer_get", Map.of("instance", "prod-eu", "path", Map.of("id", "c-0")));

        JsonNode started = call(server, "orders_recording_start", Map.of("description", "why are refunds failing"));
        String id = started.path("id").asText();
        assertThat(id).matches("r-\\d{8}-\\d{6}-[0-9a-f]{6}");
        assertThat(fails(server, "orders_recording_start", Map.of("description", "again"))).as("one at a time per session").isTrue();

        target.respond(MockTarget.Response.json(200, "{\"id\":\"c-1\",\"name\":\"Ada\",\"password\":\"hunter2-x\",\"echo\":\"" + PASSWORD + "\"}"));
        call(server, "orders_customer_get", Map.of("instance", "prod-eu", "path", Map.of("id", "c-1")));

        JsonNode calls = call(server, "orders_recording_list", Map.of());
        assertThat(calls.path("items")).hasSize(1);
        JsonNode recorded = calls.at("/items/0");
        assertThat(recorded.path("tool").asText()).isEqualTo("orders_customer_get");
        assertThat(recorded.at("/request/method").asText()).isEqualTo("GET");
        assertThat(recorded.at("/request/url").asText()).endsWith("/api/v2/customers/c-1");
        assertThat(recorded.at("/request/headers").has("authorization")).isFalse();
        assertThat(recorded.at("/response/status").asInt()).isEqualTo(200);
        assertThat(recorded.at("/response/body/name").asText()).isEqualTo("Ada");
        assertThat(recorded.at("/response/body/password").asText()).isEqualTo("***");
        assertThat(recorded.at("/response/body/echo").asText()).isEqualTo("***");

        JsonNode stopped = call(server, "orders_recording_stop", Map.of("findings", "the refund endpoint needs the order's currency"));
        assertThat(stopped.path("calls").asInt()).isEqualTo(1);
        assertThat(stopped.path("stopped").isTextual()).isTrue();
        assertThat(fails(server, "orders_recording_stop", Map.of())).isTrue();
        assertThat(fails(server, "orders_recording_list", Map.of())).as("nothing running, no id").isTrue();

        JsonNode sessions = call(server, "orders_recording_sessions", Map.of());
        assertThat(sessions.at("/items/0/id").asText()).isEqualTo(id);
        assertThat(sessions.at("/items/0/findings").asText()).contains("currency");
        assertThat(call(server, "orders_recording_list", Map.of("session", id)).path("items")).hasSize(1);
        assertThat(fails(server, "orders_recording_list", Map.of("session", "../../etc"))).isTrue();

        // on disk: the same redacted data, owner-only
        Path stored = dir.resolve("data/recordings").resolve(id);
        String everything;
        try (Stream<Path> files = Files.walk(stored)) {
            everything = files.filter(Files::isRegularFile).map(p -> {
                try {
                    return Files.readString(p);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).reduce("", String::concat);
        }
        assertThat(everything).doesNotContain(PASSWORD).doesNotContain("hunter2-x").doesNotContain("Basic ");
        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(stored))).isEqualTo("rwx------");
    }

    @Test
    void oldRecordingsAreRemovedAtStart() throws Exception {
        Path root = dir.resolve("recordings");
        Recordings early = new Recordings(root, Set.of(), Set.of(), Set.of(), 1024, 14,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        String old = early.start(null, "an old one").get("id").toString();
        early.stop(null, null);
        new Recordings(root, Set.of(), Set.of(), Set.of(), 1024, 14, Clock.fixed(Instant.parse("2026-01-10T00:00:00Z"), ZoneOffset.UTC));
        assertThat(root.resolve(old)).exists();
        new Recordings(root, Set.of(), Set.of(), Set.of(), 1024, 14, Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC));
        assertThat(root.resolve(old)).doesNotExist();
    }
}
