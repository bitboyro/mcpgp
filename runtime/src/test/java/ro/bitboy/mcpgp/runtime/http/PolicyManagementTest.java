package ro.bitboy.mcpgp.runtime.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import ro.bitboy.mcpgp.runtime.McpgpServer;
import ro.bitboy.mcpgp.runtime.PolicyManagement;
import ro.bitboy.mcpgp.runtime.Reloader;
import ro.bitboy.mcpgp.runtime.Startup;
import ro.bitboy.mcpgp.runtime.call.MockTarget;
import ro.bitboy.mcpgp.runtime.config.ConfigException;
import ro.bitboy.mcpgp.schemas.SchemaKind;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;

class PolicyManagementTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final String ADMIN = "administrator-one-0123456789abcdef";
    static final String OTHER = "administrator-two-0123456789abcdef";
    static final String CLIENT = "mcp-client-key-0123456789abcdef";
    @TempDir Path dir;
    ConfigurableApplicationContext context;
    MockTarget target;
    Path file;
    int port;
    int managementPort;
    String[] args;
    Map<String, String> env;

    @AfterEach void stop() {
        if (context != null) { context.close(); }
        if (target != null) { target.close(); }
    }

    static int port() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    void start(boolean enabled, boolean separate, Map<String, String> overrides) throws Exception {
        start(enabled, separate, overrides, false);
    }

    void start(boolean enabled, boolean separate, Map<String, String> overrides, boolean json) throws Exception {
        target = new MockTarget();
        port = port();
        managementPort = separate ? port() : port;
        file = dir.resolve("orders-mcp.yaml");
        Files.writeString(file, """
                # Customer configuration: secret references must stay references.
                server:
                  transport: http
                  port: %d
                  auth:
                    mode: static
                    keys: [{name: client, value: "${env:CLIENT}"}]
                  management:
                    enabled: %s
                %s
                    keys:
                      - {name: admin, value: "${env:ADMIN}"}
                      - {name: other, value: "${env:OTHER}"}
                instances:
                  - {id: production, baseUrl: "%s", contract: "2.0"}
                """.formatted(port, enabled, separate ? "    port: " + managementPort : "", target.address()));
        if (json) {
            JsonNode source = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readTree(file.toFile());
            Path jsonFile = dir.resolve("orders-mcp.json");
            JSON.writerWithDefaultPrettyPrinter().writeValue(jsonFile.toFile(), source);
            Files.delete(file);
            file = jsonFile;
        }
        java.util.HashMap<String, String> environment = new java.util.HashMap<>(Map.of("ADMIN", ADMIN, "OTHER", OTHER, "CLIENT", CLIENT));
        environment.putAll(overrides);
        env = Map.copyOf(environment);
        args = new String[] {"--manifest", REPO.resolve("examples/manifest/orders-mcp.manifest.json").toString(),
                "--config", file.toString(), "--storage.root", dir.resolve("data").toString()};
        Startup startup = Startup.load(args, env, dir);
        context = McpgpServer.start(startup, new McpgpServer.Stdio(System.in, System.out, () -> { }));
    }

    HttpResponse<String> request(int toPort, String path, String key, String body, Map<String, String> extra) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + toPort + path)).timeout(Duration.ofSeconds(10));
        if (key != null) { builder.header("Authorization", "Bearer " + key); }
        extra.forEach(builder::header);
        if (body == null) { builder.GET(); }
        else { builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)); }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> request(String path, String key, Object body) throws Exception {
        return request(managementPort, "/management/policy" + path, key, body == null ? null : JSON.writeValueAsString(body), Map.of());
    }

    JsonNode parsed(HttpResponse<String> response) throws Exception { return JSON.readTree(response.body()); }
    String revision() throws Exception { return parsed(request("", ADMIN, null)).path("revision").asText(); }
    Map<String, Object> previewBody(String revision, Object value) {
        return Map.of("expectedRevision", revision, "changes", List.of(Map.of("path", "/tools", "value", value)));
    }
    Reloader reloader() { return context.getBean(Reloader.class); }

    @Test void disabledByDefaultAndMcpCredentialsCannotAdminister() throws Exception {
        start(false, false, Map.of());
        assertThat(request("", ADMIN, null).statusCode()).isEqualTo(404);
        context.close(); context = null;
        target.close(); target = null;
        start(true, false, Map.of());
        assertThat(request("", null, null).statusCode()).isEqualTo(401);
        assertThat(request("", CLIENT, null).statusCode()).isEqualTo(401);
        var authorized = request("", ADMIN, null);
        assertThat(authorized.statusCode()).isEqualTo(200);
        assertThat(authorized.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(authorized.body()).doesNotContain(ADMIN, CLIENT, OTHER, "baseUrl", "password");
    }

    @Test void managementPortAndOriginAreEnforced() throws Exception {
        start(true, true, Map.of());
        assertThat(request(port, "/management/policy", ADMIN, null, Map.of()).statusCode()).isEqualTo(404);
        assertThat(request("", ADMIN, null).statusCode()).isEqualTo(200);
        assertThat(request(managementPort, "/mcp", CLIENT, null, Map.of()).statusCode()).isEqualTo(404);
        assertThat(request(managementPort, "/management/policy", ADMIN, null, Map.of("Origin", "https://example.com")).statusCode()).isEqualTo(403);
    }

    @Test void previewDoesNotWriteOrProbeAndApplyPersistsReferencesAndAudit() throws Exception {
        start(true, false, Map.of());
        byte[] original = Files.readAllBytes(file);
        String revision = revision();
        var preview = request("/preview", ADMIN, previewBody(revision, Map.of("allow", List.of())));
        assertThat(preview.statusCode()).isEqualTo(200);
        JsonNode plan = parsed(preview);
        assertThat(plan.path("rewritesYamlFormatting").asBoolean()).isTrue();
        assertThat(plan.path("decisions")).isNotEmpty();
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
        assertThat(target.received).isEmpty();
        assertThat(reloader().current().catalog().tool("orders_customer_get")).isPresent();
        var apply = request("/apply", ADMIN, Map.of("expectedRevision", revision, "previewId", plan.path("previewId").asText()));
        assertThat(apply.statusCode()).as(apply.body()).isEqualTo(200);
        assertThat(parsed(apply).path("persisted").asBoolean()).isTrue();
        assertThat(parsed(apply).path("revision").asText()).isNotEqualTo(revision);
        assertThat(reloader().current().catalog().tool("orders_customer_get")).isEmpty();
        assertThat(Files.readString(file)).contains("${env:ADMIN}", "${env:CLIENT}").doesNotContain(ADMIN, CLIENT, OTHER);
        Startup recovered = Startup.load(args, env, dir);
        assertThat(recovered.config().document().at("/tools/allow")).isEmpty();
        assertThat(recovered.config().configHash()).isEqualTo(reloader().current().config().configHash());
        String audit = Files.readString(dir.resolve("data/audit/audit.jsonl"));
        assertThat(audit).contains("config.management", "\"action\":\"preview\"", "\"action\":\"apply\"", "\"auth\":\"management\"")
                .doesNotContain(ADMIN, CLIENT, OTHER);
        Schemas schemas = new Schemas();
        for (String line : audit.lines().toList()) {
            assertThat(schemas.problems(SchemaKind.AUDIT_EVENT, JSON.readTree(line))).as(line).isEmpty();
        }
    }

    @Test void previewsAreBoundToAnAdministratorAndCannotBeReplayed() throws Exception {
        start(true, false, Map.of());
        String revision = revision();
        JsonNode plan = parsed(request("/preview", ADMIN, previewBody(revision, Map.of("allow", List.of()))));
        Object apply = Map.of("expectedRevision", revision, "previewId", plan.path("previewId").asText());
        assertThat(request("/apply", OTHER, apply).statusCode()).isEqualTo(409);
        assertThat(request("/apply", ADMIN, apply).statusCode()).isEqualTo(200);
        assertThat(request("/apply", ADMIN, apply).statusCode()).isEqualTo(409);
    }

    @Test void externalFileEditsAndCompetingAdministratorsInvalidatePreviews() throws Exception {
        start(true, false, Map.of());
        String revision = revision();
        JsonNode plan = parsed(request("/preview", ADMIN, previewBody(revision, Map.of("allow", List.of()))));
        Files.writeString(file, Files.readString(file) + "# external edit\n");
        assertThat(request("/apply", ADMIN, Map.of("expectedRevision", revision, "previewId", plan.path("previewId").asText())).statusCode()).isEqualTo(409);
        revision = revision();
        JsonNode first = parsed(request("/preview", ADMIN, previewBody(revision, Map.of("allow", List.of()))));
        JsonNode second = parsed(request("/preview", OTHER, previewBody(revision, Map.of("deny", List.of("orders_customer_get")))));
        final String base = revision;
        CompletableFuture<Integer> one = CompletableFuture.supplyAsync(() -> applyStatus(base, first, ADMIN));
        CompletableFuture<Integer> two = CompletableFuture.supplyAsync(() -> applyStatus(base, second, OTHER));
        assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder(200, 409);
    }

    int applyStatus(String revision, JsonNode plan, String key) {
        try {
            return request("/apply", key, Map.of("expectedRevision", revision, "previewId", plan.path("previewId").asText())).statusCode();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test void unsupportedPathsInvalidNamesAndMaskedWritesAreRejected() throws Exception {
        start(true, false, Map.of("ORDERS_MCP_TOOLS__ALLOW", "[\"orders_customer_get\"]"));
        String revision = revision();
        assertThat(parsed(request("/preview", ADMIN, previewBody(revision, Map.of("allow", List.of())))).path("code").asText()).isEqualTo("POLICY_OVERRIDDEN");
        assertThat(request("/preview", ADMIN, previewBody(revision, Map.of("deny", List.of("unknown_tool")))).statusCode()).isEqualTo(422);
        assertThat(request("/preview", ADMIN, Map.of("expectedRevision", revision, "changes", List.of(Map.of("path", "/server/auth", "value", Map.of())))).statusCode()).isEqualTo(400);
        assertThat(request("/preview", ADMIN, previewBody(revision, Map.of("allow", List.of("${file:/etc/passwd}")))).statusCode()).isEqualTo(400);
        assertThat(reloader().current().catalog().tool("orders_customer_get")).isPresent();
    }

    @Test void malformedDuplicateDeepAndOversizedBodiesAreBounded() throws Exception {
        start(true, false, Map.of());
        for (String malformed : List.of("{", "{}{}", "{\"expectedRevision\":\"a\",\"expectedRevision\":\"b\"}", "[".repeat(50) + "]".repeat(50))) {
            assertThat(request(managementPort, "/management/policy/preview", ADMIN, malformed, Map.of()).statusCode()).isEqualTo(400);
        }
        assertThat(request(managementPort, "/management/policy/preview", ADMIN, " ".repeat(65537), Map.of()).statusCode()).isEqualTo(413);
        var plain = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/management/policy/preview"))
                .header("Authorization", "Bearer " + ADMIN).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertThat(HTTP.send(plain, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(415);
    }

    @Test void persistenceFailureLeavesDiskAndActiveSnapshotUnchanged() throws Exception {
        start(true, false, Map.of());
        byte[] original = Files.readAllBytes(file);
        String active = reloader().current().config().configHash();
        PolicyManagement management = new PolicyManagement(reloader(), Clock.systemUTC(), (path, before, candidate) -> { throw new IOException("disk full"); });
        String revision = management.read("admin").get("revision").toString();
        var preview = management.preview(JSON.valueToTree(previewBody(revision, Map.of("allow", List.of()))), "admin");
        assertThatThrownBy(() -> management.apply(JSON.valueToTree(Map.of("expectedRevision", revision, "previewId", preview.get("previewId"))), "admin"))
                .isInstanceOfSatisfying(PolicyManagement.Failure.class, e -> assertThat(e.code()).isEqualTo("CONFIG_STORAGE_UNAVAILABLE"));
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
        assertThat(reloader().current().config().configHash()).isEqualTo(active);
        assertThat(reloader().current().catalog().tool("orders_customer_get")).isPresent();
    }

    @Test void expiredPreviewsCannotBeApplied() throws Exception {
        start(true, false, Map.of());
        class MutableClock extends Clock {
            Instant now = Instant.now();
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now; }
        }
        MutableClock clock = new MutableClock();
        PolicyManagement management = new PolicyManagement(reloader(), clock, (path, before, candidate) -> { throw new AssertionError("must not persist"); });
        String revision = management.read("admin").get("revision").toString();
        var plan = management.preview(JSON.valueToTree(previewBody(revision, Map.of("allow", List.of()))), "admin");
        clock.now = clock.now.plus(Duration.ofMinutes(11));
        assertThatThrownBy(() -> management.apply(JSON.valueToTree(Map.of("expectedRevision", revision, "previewId", plan.get("previewId"))), "admin"))
                .isInstanceOfSatisfying(PolicyManagement.Failure.class, e -> assertThat(e.code()).isEqualTo("PREVIEW_EXPIRED"));
    }

    @Test void jsonInstanceEditsCanBeRemovedToRestoreInheritance() throws Exception {
        start(true, false, Map.of(), true);
        var permissions = Files.getPosixFilePermissions(file);
        String revision = revision();
        JsonNode edit = JSON.createObjectNode().put("expectedRevision", revision).set("changes", JSON.createArrayNode()
                .add(JSON.createObjectNode().put("path", "/instances/0/tools").set("value", JSON.createObjectNode().set("allow", JSON.createArrayNode()))));
        JsonNode plan = parsed(request("/preview", ADMIN, edit));
        assertThat(plan.path("rewritesYamlFormatting").asBoolean()).isFalse();
        assertThat(request("/apply", ADMIN, Map.of("expectedRevision", revision, "previewId", plan.path("previewId").asText())).statusCode()).isEqualTo(200);
        assertThat(reloader().current().catalog().tool("orders_customer_get")).isEmpty();
        revision = revision();
        JsonNode removal = JSON.createObjectNode().put("expectedRevision", revision).set("changes", JSON.createArrayNode()
                .add(JSON.createObjectNode().put("path", "/instances/0/tools").putNull("value")));
        JsonNode removePlan = parsed(request("/preview", ADMIN, removal));
        assertThat(request("/apply", ADMIN, Map.of("expectedRevision", revision, "previewId", removePlan.path("previewId").asText())).statusCode()).isEqualTo(200);
        assertThat(reloader().current().catalog().tool("orders_customer_get")).isPresent();
        assertThat(JSON.readTree(file.toFile()).at("/instances/0/tools").isMissingNode()).isTrue();
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(permissions);
        assertThat(Files.readString(file)).contains("${env:ADMIN}").doesNotContain(ADMIN);
    }

    @Test void aReferencedPolicyChangingInDotEnvInvalidatesItsPreview() throws Exception {
        start(true, false, Map.of());
        Files.writeString(dir.resolve(".env"), "ALLOWED_TOOL=orders_customer_get\n");
        Files.writeString(file, Files.readString(file) + "tools: {allow: ['${env:ALLOWED_TOOL}']}\n");
        assertThat(reloader().reload("test")).isTrue();
        String revision = revision();
        JsonNode edit = JSON.createObjectNode().put("expectedRevision", revision).set("changes", JSON.createArrayNode()
                .add(JSON.createObjectNode().put("path", "/instances/0/tools").set("value", JSON.createObjectNode().set("allow", JSON.createArrayNode()))));
        JsonNode plan = parsed(request("/preview", ADMIN, edit));
        Files.writeString(dir.resolve(".env"), "ALLOWED_TOOL=orders_customer_list\n");
        var apply = request("/apply", ADMIN, Map.of("expectedRevision", revision, "previewId", plan.path("previewId").asText()));
        assertThat(apply.statusCode()).as(apply.body()).isEqualTo(409);
        assertThat(parsed(apply).path("code").asText()).isEqualTo("CONFIG_REVISION_CONFLICT");
    }

    @Test void aSymlinkSourceIsNotReplaced() throws Exception {
        start(true, false, Map.of());
        Path real = dir.resolve("real-config.yaml");
        Files.copy(file, real);
        byte[] original = Files.readAllBytes(real);
        Files.delete(file);
        Files.createSymbolicLink(file, real);
        assertThat(request("", ADMIN, null).statusCode()).isEqualTo(503);
        assertThat(Files.isSymbolicLink(file)).isTrue();
        assertThat(Files.readAllBytes(real)).isEqualTo(original);
    }

    @Test void administrationRequiresIndependentStrongKeysAndAuditLogging() throws Exception {
        start(true, false, Map.of());
        context.close(); context = null;
        Map<String, String> clientAsAdmin = new java.util.HashMap<>(env);
        clientAsAdmin.put("ADMIN", CLIENT);
        assertThatThrownBy(() -> Startup.load(args, clientAsAdmin, dir)).isInstanceOf(ConfigException.class).hasMessageContaining("must differ from MCP client keys");
        Map<String, String> shortKey = new java.util.HashMap<>(env);
        shortKey.put("ADMIN", "short");
        assertThatThrownBy(() -> Startup.load(args, shortKey, dir)).isInstanceOf(ConfigException.class).hasMessageContaining("at least 24");
        Files.writeString(file, Files.readString(file) + "audit: {file: {enabled: false}}\n");
        assertThatThrownBy(() -> Startup.load(args, env, dir)).isInstanceOf(ConfigException.class).hasMessageContaining("requires file audit logging");
    }
}
