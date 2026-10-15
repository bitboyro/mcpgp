package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.config.ConfigException;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.mcp.McpServerFactory;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Instances on different contract versions (definition-format §2 instances.mixedContracts,
 * tools-contract §3–§4, runtime-config §7), and instances.max, with the orders example: contracts 1.0
 * and 2.0 (default), some tools only in 2.0.
 */
class MixedContractsTest {

    static final Path ORDERS_FILE = Path.of("..").toAbsolutePath().normalize().resolve("examples/manifest/orders-mcp.manifest.json");
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;
    MockTarget prod;
    MockTarget legacy;

    @BeforeEach
    void targets() throws IOException {
        prod = new MockTarget();
        legacy = new MockTarget();
    }

    @AfterEach
    void stop() {
        prod.close();
        legacy.close();
    }

    Manifest orders(String mixed, Integer max) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(ORDERS_FILE.toFile());
        ObjectNode instances = (ObjectNode) root.path("instances");
        instances.put("mixedContracts", mixed);
        if (max != null) {
            instances.put("max", max);
        }
        return Manifest.of(root);
    }

    McpServerFactory server(Manifest manifest, String instances) throws IOException {
        Path file = dir.resolve("orders-mcp.yaml");
        Files.writeString(file, "instances:\n" + instances.replace("{prod}", prod.address()).replace("{legacy}", legacy.address()));
        RuntimeConfig config = new ConfigLoader(new Schemas()).load(manifest, new ConfigLoader.Options(Optional.of(file), dir,
                Map.of(), Map.of("storage.root", dir.resolve("data").toString())));
        return new McpServerFactory(manifest, config);
    }

    static McpSchema.CallToolResult call(McpServerFactory server, String tool, Map<String, Object> arguments) {
        return server.call(null, new McpSchema.CallToolRequest(tool, arguments, null));
    }

    static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    static final String BOTH = """
              - { id: prod, baseUrl: "{prod}", contract: "2.0" }
              - { id: legacy, baseUrl: "{legacy}", contract: "1.0" }
            """;

    // ------------------------------------------------------------------ namespaced

    @Test
    void namespacedToolsAreOnePerInstanceWithoutAnInstanceInput() throws IOException {
        McpServerFactory server = server(orders("namespaced", null), BOTH);

        assertThat(server.catalog().tool("orders_customer_get")).as("no shared tool").isEmpty();
        JsonNode onProd = server.catalog().tool("prod__orders_customer_get").orElseThrow();
        JsonNode onLegacy = server.catalog().tool("legacy__orders_customer_get").orElseThrow();
        assertThat(onProd.at("/inputSchema/properties").has("instance")).isFalse();
        assertThat(onProd.at("/inputSchema/required").toString()).doesNotContain("instance");
        assertThat(onLegacy.path("description").asText()).contains("legacy").contains("1.0");

        McpSchema.CallToolResult result = call(server, "legacy__orders_customer_get", Map.of("path", Map.of("id", "c-1")));
        assertThat(result.isError()).as(text(result)).isFalse();
        assertThat(legacy.received).extracting(MockTarget.Received::path).containsExactly("/api/v1/customers/c-1");
        assertThat(prod.received).isEmpty();
    }

    @Test
    void aNamespacedInstanceOnlyGetsTheToolsOfItsVersion() throws IOException {
        McpServerFactory server = server(orders("namespaced", null), BOTH);

        assertThat(server.catalog().tool("prod__orders_overdue_invoice_list")).isPresent();
        assertThat(server.catalog().tool("legacy__orders_overdue_invoice_list")).as("2.0 only").isEmpty();
        assertThat(server.catalog().listed()).extracting(t -> t.path("name").asText()).contains("instances_list");
    }

    @Test
    void aNamespacedToolHasOnlyItsVersionsFields() throws IOException {
        McpServerFactory server = server(orders("namespaced", null), BOTH);
        // expand exists only in 2.0
        assertThat(server.catalog().tool("prod__orders_customer_get").orElseThrow().at("/inputSchema/properties/query/properties").has("expand")).isTrue();
        assertThat(server.catalog().tool("legacy__orders_customer_get").orElseThrow().at("/inputSchema/properties/query/properties").has("expand")).isFalse();
        assertThat(server.catalog().tool("prod__orders_customer_get").orElseThrow().toString()).doesNotContain("(2.0 only)");
        for (JsonNode tool : server.catalog().all().values()) {
            if (!tool.path("name").asText().startsWith("legacy__")) {
                continue;
            }
            assertThat(tool.path("inputSchema").toString()).as(tool.path("name").asText()).doesNotContain("\"2.0\"");
        }
    }

    // ------------------------------------------------------------------ forbid

    @Test
    void forbidRefusesPinnedInstancesOnDifferentVersions() {
        assertThatThrownBy(() -> server(orders("forbid", null), BOTH))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("mixedContracts: forbid")
                .hasMessageContaining("legacy").hasMessageContaining("1.0").hasMessageContaining("2.0");
    }

    @Test
    void forbidAcceptsInstancesOnTheSameVersion() throws IOException {
        McpServerFactory server = server(orders("forbid", null), """
                  - { id: prod, baseUrl: "{prod}", contract: "1.0" }
                  - { id: legacy, baseUrl: "{legacy}", contract: "1.0" }
                """);
        assertThat(call(server, "orders_customer_get", Map.of("instance", "prod", "path", Map.of("id", "c-1"))).isError()).isFalse();
    }

    @Test
    void forbidMakesADetectedInstanceOnAnotherVersionUnavailable() throws IOException {
        legacy.respondToVersion(MockTarget.Response.json(404, "{}")).respondToVersion(MockTarget.Response.json(200, "{\"version\":\"1.0.4\"}"));
        McpServerFactory server = server(orders("forbid", null), """
                  - { id: prod, baseUrl: "{prod}", contract: "2.0" }
                  - { id: legacy, baseUrl: "{legacy}" }
                """);

        McpSchema.CallToolResult onLegacy = call(server, "orders_customer_get", Map.of("instance", "legacy", "path", Map.of("id", "c-1")));
        McpSchema.CallToolResult onProd = call(server, "orders_customer_get", Map.of("instance", "prod", "path", Map.of("id", "c-1")));

        assertThat(JSON.valueToTree(onLegacy.structuredContent()).path("code").asText()).isEqualTo("INSTANCE_UNAVAILABLE");
        assertThat(text(onLegacy)).contains("1.0").contains("mixedContracts: forbid");
        assertThat(legacy.received).isEmpty();
        assertThat(onProd.isError()).isFalse();
    }

    // ------------------------------------------------------------------ union, the default

    @Test
    void unionKeepsOneToolForEveryInstance() throws IOException {
        McpServerFactory server = server(orders("union", null), BOTH);
        assertThat(server.catalog().tool("orders_customer_get")).isPresent();
        assertThat(server.catalog().tool("prod__orders_customer_get")).isEmpty();
    }

    // ------------------------------------------------------------------ instances.max

    @Test
    void moreInstancesThanTheBuildAllowsStopStartup() {
        assertThatThrownBy(() -> server(orders("union", 1), BOTH))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("2 instances").hasMessageContaining("at most 1").hasMessageContaining("instances.max");
    }

    @Test
    void upToTheMaximumIsFine() throws IOException {
        assertThat(server(orders("union", 2), BOTH).catalog().tool("orders_customer_get")).isPresent();
    }
}
