package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.audit.AuditLog;
import ro.bitboy.mcpgp.runtime.call.MockTarget;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.schemas.SchemaKind;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Confirmation of destructive tools (tools-contract §8) and the audit file (audit-and-errors §1–2). */
class ConfirmAndAuditTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Manifest NOTES = Manifest.file(REPO.resolve("conformance/cases/safety/confirm-destructive/expected/manifest.json"));
    static final Manifest ORDERS = Manifest.file(REPO.resolve("examples/manifest/orders-mcp.manifest.json"));
    static final ObjectMapper JSON = new ObjectMapper();
    static final Schemas SCHEMAS = new Schemas();

    /** A clock the test moves. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-10T08:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TempDir
    Path dir;
    MockTarget target;
    TestClock clock = new TestClock();
    RuntimeConfig config;
    McpServerFactory server;

    @AfterEach
    void stop() {
        target.close();
    }

    void start(Manifest manifest, String instances, String extra, Map<String, String> env) throws IOException {
        target = target == null ? new MockTarget() : target;
        Path file = dir.resolve("server.yaml");
        Files.writeString(file, instances.formatted(target.address()) + extra);
        config = new ConfigLoader(SCHEMAS).load(manifest, new ConfigLoader.Options(Optional.of(file), dir, env,
                Map.of("storage.root", dir.resolve("data").toString())));
        server = new McpServerFactory(manifest, config, new AuditLog(config.audit(), manifest.serverName(), manifest.serverVersion(), clock), clock);
    }

    void notes() throws IOException {
        start(NOTES, "instances:\n  - { id: a, baseUrl: \"%s\" }\n  - { id: b, baseUrl: \"%1$s\" }\n", "", Map.of());
    }

    McpSchema.CallToolResult call(McpSyncServerExchange exchange, String tool, Map<String, Object> arguments) {
        return server.call(exchange, new McpSchema.CallToolRequest(tool, arguments, null));
    }

    static JsonNode structured(McpSchema.CallToolResult result) {
        return JSON.valueToTree(result.structuredContent());
    }

    List<JsonNode> auditEvents() throws IOException {
        List<JsonNode> events = new ArrayList<>();
        for (String line : Files.readAllLines(config.audit().file())) {
            JsonNode event = JSON.readTree(line);
            assertThat(SCHEMAS.problems(SchemaKind.AUDIT_EVENT, event)).as("audit event %s", line).isEmpty();
            if (!event.path("type").asText().equals("instance.contract")) { // written when detection finishes, in the background
                events.add(event);
            }
        }
        return events;
    }

    static Map<String, Object> delete(String instance, String id) {
        return Map.of("instance", instance, "path", Map.of("id", id));
    }

    static Map<String, Object> with(Map<String, Object> arguments, String token) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(arguments);
        copy.put("confirm", token);
        return copy;
    }

    @Test
    void destructiveToolsRunOnlyWithAValidToken() throws IOException {
        notes();
        McpSchema.CallToolResult first = call(null, "notes_note_delete", delete("a", "n-1"));
        assertThat(structured(first).path("code").asText()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(((McpSchema.TextContent) first.content().get(0)).text()).contains("Delete note on a: this changes data");
        String token = structured(first).path("confirm").asText();
        assertThat(target.received).isEmpty();

        target.respond(new MockTarget.Response(204, Map.of(), new byte[0], 0));
        McpSchema.CallToolResult confirmed = call(null, "notes_note_delete", with(delete("a", "n-1"), token));
        assertThat(confirmed.isError()).isFalse();
        assertThat(target.last().method()).isEqualTo("DELETE");
        assertThat(target.last().path()).isEqualTo("/v1/notes/n-1");

        // single use
        McpSchema.CallToolResult reused = call(null, "notes_note_delete", with(delete("a", "n-1"), token));
        assertThat(((McpSchema.TextContent) reused.content().get(0)).text()).contains("unknown or was already used");
        assertThat(target.received).hasSize(1);
    }

    @Test
    void tokensAreBoundToTheExactCallAndExpire() throws IOException {
        notes();
        String token = structured(call(null, "notes_note_delete", delete("a", "n-1"))).path("confirm").asText();
        McpSchema.CallToolResult otherNote = call(null, "notes_note_delete", with(delete("a", "n-2"), token));
        assertThat(((McpSchema.TextContent) otherNote.content().get(0)).text()).contains("issued for a different call");

        String second = structured(call(null, "notes_note_delete", delete("b", "n-1"))).path("confirm").asText();
        McpSchema.CallToolResult otherInstance = call(null, "notes_note_delete", with(delete("a", "n-1"), second));
        assertThat(structured(otherInstance).path("code").asText()).isEqualTo("CONFIRMATION_REQUIRED");

        String third = structured(call(null, "notes_note_delete", delete("a", "n-1"))).path("confirm").asText();
        clock.now = clock.now.plus(Duration.ofMinutes(6));
        McpSchema.CallToolResult expired = call(null, "notes_note_delete", with(delete("a", "n-1"), third));
        assertThat(((McpSchema.TextContent) expired.content().get(0)).text()).contains("expired");
        assertThat(target.received).isEmpty();
    }

    @Test
    void clientsWithElicitationAreAskedDirectly() throws IOException {
        notes();
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.getClientCapabilities()).thenReturn(new McpSchema.ClientCapabilities(null, null, null,
                new McpSchema.ClientCapabilities.Elicitation(null, null)));
        when(exchange.getClientInfo()).thenReturn(new McpSchema.Implementation("desk", "1"));
        when(exchange.createElicitation(any())).thenReturn(
                new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of("confirm", true), null),
                new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.DECLINE, null, null));
        target.respond(new MockTarget.Response(204, Map.of(), new byte[0], 0));
        assertThat(call(exchange, "notes_note_delete", delete("a", "n-1")).isError()).isFalse();
        McpSchema.CallToolResult declined = call(exchange, "notes_note_delete", delete("a", "n-2"));
        assertThat(((McpSchema.TextContent) declined.content().get(0)).text()).contains("did not confirm");
        assertThat(target.received).hasSize(1);

        List<String> confirms = auditEvents().stream().filter(e -> e.path("type").asText().equals("tool.confirm"))
                .map(e -> e.path("confirm").asText()).toList();
        assertThat(confirms).containsExactly("asked", "given", "asked", "refused");
        assertThat(auditEvents()).filteredOn(e -> e.path("type").asText().equals("tool.call"))
                .allSatisfy(e -> assertThat(e.at("/actor/id").asText()).isEqualTo("desk"));
    }

    @Test
    void everyCallIsAuditedWithoutSecretsOrContents() throws IOException {
        start(ORDERS, "instances:\n  - { id: prod-eu, baseUrl: \"%s\", auth: { type: basic, username: svc, password: \"${env:PASS}\" } }\n",
                "", Map.of("PASS", "pa55word-secret"));
        target.respond(MockTarget.Response.json(200, "{\"id\":\"c-1\"}"));
        McpSchema.CallToolResult ok = call(null, "orders_customer_get", Map.of("instance", "prod-eu", "path", Map.of("id", "c-1")));
        target.respond(new MockTarget.Response(404, Map.of("Content-Type", "application/json"),
                "{\"message\":\"no c-9, pw pa55word-secret\"}".getBytes(), 0));
        call(null, "orders_customer_get", Map.of("instance", "prod-eu", "path", Map.of("id", "c-9")));
        call(null, "orders_customer_create_multipart", Map.of("instance", "prod-eu", "body", Map.of(
                "payload", Map.of("name", "Ana", "accountId", "a-1"),
                "document", Map.of("base64", Base64.getEncoder().encodeToString("%PDF-1.4".getBytes()), "filename", "c.pdf",
                        "contentType", "application/pdf"))));
        call(null, "orders_customer_get", Map.of("instance", "nope", "path", Map.of("id", "1")));

        List<JsonNode> calls = auditEvents().stream().filter(e -> e.path("type").asText().equals("tool.call")).toList();
        assertThat(calls).hasSize(4);
        JsonNode first = calls.get(0);
        assertThat(first.path("outcome").asText()).isEqualTo("ok");
        assertThat(first.path("instance").asText()).isEqualTo("prod-eu");
        assertThat(first.path("contract").asText()).isEqualTo("2.0");
        assertThat(first.at("/upstream/path").asText()).isEqualTo("/customers/{id}");     // templated: no ids
        assertThat(first.at("/upstream/status").asInt()).isEqualTo(200);
        assertThat(first.path("args")).isEqualTo(JSON.readTree("{\"instance\":\"prod-eu\",\"path\":{\"id\":\"***\"}}"));
        assertThat(first.path("cid").asText()).isEqualTo(ok.meta().get("mcpgp/correlationId"));

        JsonNode failed = calls.get(1);
        assertThat(failed.path("code").asText()).isEqualTo("UPSTREAM_4XX");
        assertThat(failed.at("/upstream/body").asText()).contains("no c-9").contains("***");
        assertThat(calls.get(2).path("files").get(0).path("name").asText()).isEqualTo("c.pdf");
        assertThat(calls.get(2).path("files").get(0).path("sha256").asText()).hasSize(64);
        assertThat(calls.get(3).path("code").asText()).isEqualTo("INSTANCE_UNKNOWN");

        String file = Files.readString(config.audit().file());
        assertThat(file).doesNotContain("pa55word-secret").doesNotContain("JVBERi0x").doesNotContain("Ana");
    }

    @Test
    void argumentsCanBeHashedOrLeftOut() throws IOException {
        start(ORDERS, "instances:\n  - { id: prod-eu, baseUrl: \"%s\" }\n", "audit: { arguments: hashed }\n", Map.of());
        call(null, "orders_customer_get", Map.of("instance", "prod-eu", "path", Map.of("id", "c-1")));
        call(null, "orders_customer_get", Map.of("path", Map.of("id", "c-1"), "instance", "prod-eu"));
        List<JsonNode> calls = auditEvents();
        assertThat(calls.get(0).at("/args/sha256").asText()).hasSize(64).isEqualTo(calls.get(1).at("/args/sha256").asText());
    }

    @Test
    void rotatesAndKeepsOnlyRecentFiles() throws IOException {
        notes();
        Path audit = config.audit().file();
        call(null, "notes_note_get", Map.of("instance", "a", "path", Map.of("id", "1")));
        clock.now = clock.now.plus(Duration.ofDays(1));
        call(null, "notes_note_get", Map.of("instance", "a", "path", Map.of("id", "1")));
        Path rotated = audit.resolveSibling("audit-2026-10-10.jsonl");
        assertThat(rotated).exists();
        assertThat(Files.readAllLines(audit)).hasSize(1);

        // a rotated file older than retainDays (30) goes at the next rotation
        Files.setLastModifiedTime(rotated, java.nio.file.attribute.FileTime.from(clock.now.minus(Duration.ofDays(31))));
        clock.now = clock.now.plus(Duration.ofDays(1));
        call(null, "notes_note_get", Map.of("instance", "a", "path", Map.of("id", "1")));
        assertThat(rotated).doesNotExist();
        assertThat(audit.resolveSibling("audit-2026-10-11.jsonl")).exists();
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(audit))).isEqualTo("rw-------");
        }
    }
}
