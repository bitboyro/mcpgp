package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.core.manifest.CanonicalJson;
import ro.bitboy.mcpgp.runtime.call.MockTarget;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.manifest.ManifestException;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Custom tools, request enrichers and call filters (spec/extensions.md), from definition to call. */
class ExtensionsTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();
    static final String EXTENSIONS = """
            extensions:
              artifacts: [ { path: ext/acme-ext.jar } ]
              customTools:
                - name: account_count
                  title: Account count
                  description: How many accounts there are.
                  inputSchema: { type: object, properties: {} }
                  annotations: { readOnlyHint: true }
                  handler: com.example.ext.AccountCount
              enrichers:
                - { handler: com.example.ext.TenantHeader }
              callFilters:
                - { handler: com.example.ext.RefundLimit, match: { operationId: refundOrder } }
            """;

    @TempDir
    Path dir;
    MockTarget target;
    Path last;

    @AfterEach
    void stop() {
        if (target != null) {
            target.close();
        }
    }

    Path project(String extensions) throws Exception {
        Path project = dir.resolve("project-" + System.nanoTime());
        last = project;
        Files.createDirectories(project.resolve("contracts"));
        Files.copy(REPO.resolve("examples/platform/orders/contracts/orders-2.0.yaml"), project.resolve("contracts/orders-2.0.yaml"));
        ExtensionJar.build(dir.resolve("build"), project.resolve("ext/acme-ext.jar"), ExtensionJar.SAMPLE);
        Files.writeString(project.resolve("orders.mcpgp.yaml"), """
                apiVersion: mcpgp/v1
                kind: McpServer
                metadata: { name: orders-mcp, title: Orders }
                apis:
                  - id: orders
                    toolPrefix: orders
                    contracts: [ { version: "2.0", spec: contracts/orders-2.0.yaml } ]
                    select: { exclude: [ { tag: [system, users-roles] } ] }
                instances: { mode: single }
                %s
                """.formatted(extensions));
        return project;
    }

    Compiler.Result compile(String extensions) throws Exception {
        return new Compiler(new Schemas()).compile(project(extensions).resolve("orders.mcpgp.yaml"), Compiler.Options.defaults());
    }

    McpServerFactory server(Compiler.Result result, Path manifestFolder) throws Exception {
        Files.createDirectories(manifestFolder.resolve("ext"));
        if (!Files.exists(manifestFolder.resolve("ext/acme-ext.jar"))) {
            Files.copy(last.resolve("ext/acme-ext.jar"), manifestFolder.resolve("ext/acme-ext.jar"));   // as mcpgp test does
        }
        Path manifestFile = manifestFolder.resolve("manifest.json");
        Files.writeString(manifestFile, CanonicalJson.pretty(result.manifest().orElseThrow()));
        target = new MockTarget();
        Path config = dir.resolve("orders-mcp.yaml");
        Files.writeString(config, "instance: { baseUrl: \"" + target.address() + "\" }\n");
        Manifest manifest = Manifest.file(manifestFile);
        RuntimeConfig runtime = new ConfigLoader(new Schemas()).load(manifest, new ConfigLoader.Options(Optional.of(config), dir, Map.of(),
                Map.of("storage.root", dir.resolve("data").toString())));
        return new McpServerFactory(manifest, runtime);
    }

    static McpSchema.CallToolResult call(McpServerFactory server, String tool, Map<String, Object> arguments) {
        return server.call(null, new McpSchema.CallToolRequest(tool, arguments));
    }

    @Test
    void theCompilerRecordsExtensionsWithChecksums() throws Exception {
        JsonNode manifest = compile(EXTENSIONS).manifest().orElseThrow();
        assertThat(manifest.at("/extensions/artifacts/0/path").asText()).isEqualTo("ext/acme-ext.jar");
        assertThat(manifest.at("/extensions/artifacts/0/sha256").asText()).startsWith("sha256:");
        assertThat(manifest.at("/extensions/enrichers/0/handler").asText()).isEqualTo("com.example.ext.TenantHeader");
        assertThat(manifest.at("/extensions/callFilters/0/tools/0").asText()).isEqualTo("orders_order_refund");
        JsonNode custom = null;
        for (JsonNode t : manifest.path("tools")) {
            if (t.path("kind").asText().equals("custom")) {
                custom = t;
            }
        }
        assertThat(custom.path("name").asText()).isEqualTo("orders_account_count");   // named like pack tools
        assertThat(custom.at("/annotations/readOnlyHint").asBoolean()).isTrue();

        Compiler.Result missing = compile(EXTENSIONS.replace("ext/acme-ext.jar", "ext/nope.jar"));
        assertThat(missing.manifest()).isEmpty();
        Compiler.Result noJar = compile("""
                extensions:
                  enrichers: [ { handler: com.example.ext.TenantHeader } ]
                """);
        assertThat(noJar.diagnostics()).filteredOn(d -> d.level() == Diagnostic.Level.ERROR).extracting(Diagnostic::code).contains("EXTENSION_INVALID");
    }

    @Test
    void customToolsEnrichersAndFiltersRunWhereTheSpecSays() throws Exception {
        McpServerFactory server = server(compile(EXTENSIONS), dir.resolve("run"));

        // the custom tool calls an API tool through the normal pipeline
        target.respond(MockTarget.Response.json(200, "[{\"id\":\"a-1\"},{\"id\":\"a-2\"}]"));
        McpSchema.CallToolResult count = call(server, "orders_account_count", Map.of());
        assertThat(count.isError()).as("%s", count).isFalse();
        assertThat(JSON.valueToTree(count.structuredContent()).path("tool").asText()).isEqualTo("orders_account_count");
        assertThat(JSON.valueToTree(count.structuredContent()).path("accounts").asInt()).as("%s", count.structuredContent()).isEqualTo(2);

        // the enricher set a header and a query parameter, and couldn't touch Authorization
        assertThat(target.last().header("X-Tenant")).isEqualTo("acme-default");
        assertThat(target.last().header("X-Auth-Refused")).isEqualTo("yes");
        assertThat(target.last().header("Authorization")).isNull();
        assertThat(target.last().query()).contains("source=mcp");

        // the filter stops a large refund before anything is sent, and changes the result of a small one
        int before = target.received.size();
        McpSchema.CallToolResult large = call(server, "orders_order_refund", Map.of("path", Map.of("id", "o-1"), "body", Map.of("amount", 500)));
        assertThat(large.isError()).isTrue();
        assertThat(JSON.valueToTree(large.structuredContent()).path("code").asText()).isEqualTo("REFUND_TOO_LARGE");
        assertThat(target.received).hasSize(before);
        target.respond(MockTarget.Response.json(200, "{\"id\":\"o-1\",\"status\":\"refunded\"}"));
        McpSchema.CallToolResult small = call(server, "orders_order_refund", Map.of("path", Map.of("id", "o-1"), "body", Map.of("amount", 5)));
        assertThat(JSON.valueToTree(small.structuredContent()).path("checkedBy").asText()).isEqualTo("RefundLimit");
        // a validation error from inside the chain reaches the agent as it was
        McpSchema.CallToolResult invalid = call(server, "orders_order_refund", Map.of("path", Map.of("id", "o-1"), "body", Map.of("amount", "lots")));
        assertThat(JSON.valueToTree(invalid.structuredContent()).path("code").asText()).isEqualTo("INPUT_VALIDATION");
    }

    @Test
    void aChangedJarStopsTheServer() throws Exception {
        Compiler.Result result = compile(EXTENSIONS);
        Path run = dir.resolve("run");
        Files.createDirectories(run.resolve("ext"));
        Files.write(run.resolve("ext/acme-ext.jar"), new byte[] {1, 2, 3});
        assertThatThrownBy(() -> server(result, run)).isInstanceOf(ManifestException.class).hasMessageContaining("does not match its checksum");
        Files.copy(last.resolve("ext/acme-ext.jar"), run.resolve("ext/acme-ext.jar"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path manifestFolder = dir.resolve("run2");
        Files.createDirectories(manifestFolder.resolve("ext"));
        Files.copy(last.resolve("ext/acme-ext.jar"), manifestFolder.resolve("ext/acme-ext.jar"));
        JsonNode broken = result.manifest().get().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) broken.at("/extensions/enrichers/0")).put("handler", "com.example.ext.Missing");
        Files.writeString(manifestFolder.resolve("manifest.json"), CanonicalJson.pretty(broken));
        assertThatThrownBy(() -> new McpServerFactory(Manifest.file(manifestFolder.resolve("manifest.json")), new ConfigLoader(new Schemas()).load(
                Manifest.file(manifestFolder.resolve("manifest.json")), new ConfigLoader.Options(Optional.empty(), dir, Map.of(),
                        Map.of("storage.root", dir.resolve("data").toString(), "instance.baseUrl", "http://127.0.0.1:1")))))
                .isInstanceOf(ManifestException.class).hasMessageContaining("com.example.ext.Missing");
    }
}
