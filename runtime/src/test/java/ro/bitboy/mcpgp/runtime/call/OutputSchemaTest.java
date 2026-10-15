package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.mcp.McpServerFactory;
import ro.bitboy.mcpgp.runtime.mcp.ToolCatalog;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** Tools' output schemas and structured results (tools-contract §6), on by default. */
class OutputSchemaTest {

    static final Path ORDERS = Path.of("..").toAbsolutePath().normalize().resolve("examples/manifest/orders-mcp.manifest.json");
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;
    MockTarget target;

    @BeforeEach
    void start() throws IOException {
        target = new MockTarget();
    }

    @AfterEach
    void stop() {
        target.close();
    }

    McpServerFactory server(Consumer<ObjectNode> change) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(ORDERS.toFile());
        change.accept(root);
        Path file = dir.resolve("orders-mcp.yaml");
        Files.writeString(file, "instances:\n  - { id: prod, baseUrl: \"" + target.address() + "\", contract: \"2.0\" }\n");
        Manifest manifest = Manifest.of(root);
        RuntimeConfig config = new ConfigLoader(new Schemas()).load(manifest, new ConfigLoader.Options(Optional.of(file), dir,
                Map.of(), Map.of("storage.root", dir.resolve("data").toString())));
        return new McpServerFactory(manifest, config);
    }

    static McpSchema.CallToolResult list(McpServerFactory server) {
        return server.call(null, new McpSchema.CallToolRequest("orders_customer_list",
                Map.of("instance", "prod", "query", Map.of("accountId", "a-1")), null));
    }

    @Test
    void toolsListTheirOutputSchema() throws IOException {
        McpServerFactory server = server(r -> { });
        JsonNode tool = server.catalog().tool("orders_customer_list").orElseThrow();

        McpSchema.Tool served = ToolCatalog.toMcp(tool);

        assertThat(served.outputSchema()).isNotNull().containsEntry("type", "object");
        assertThat(JSON.valueToTree(served.outputSchema()).at("/properties/data/items/properties").has("email")).isTrue();
        assertThat(JSON.valueToTree(served.outputSchema()).toString()).as("loose: real answers vary").doesNotContain("\"required\"");
    }

    @Test
    void aListAnswerIsStructuredAsItems() throws IOException {
        McpServerFactory server = server(r -> { });
        target.respond(MockTarget.Response.json(200, "[{\"id\":\"c-1\"},{\"id\":\"c-2\"}]"));

        McpSchema.CallToolResult result = list(server);

        assertThat(result.isError()).isFalse();
        assertThat(JSON.valueToTree(result.structuredContent()).path("items")).hasSize(2);
    }

    @Test
    void aToolWithAnOutputSchemaNeverAnswersWithTextOnly() throws IOException {
        McpServerFactory server = server(r -> ((ObjectNode) r.path("policies")).put("responseLimitKb", 1).put("cleanTruncation", false));
        target.respond(MockTarget.Response.json(200, "{\"data\":[" + "{\"id\":\"c-1\",\"name\":\"a long enough name\"},".repeat(100) + "{}]}"));

        McpSchema.CallToolResult result = list(server);

        assertThat(result.isError()).isTrue();
        assertThat(JSON.valueToTree(result.structuredContent()).path("code").asText()).isEqualTo("RESPONSE_TOO_LARGE");
    }

    @Test
    void withoutOutputSchemasATextAnswerIsFine() throws IOException {
        McpServerFactory server = server(r -> {
            ((ObjectNode) r.path("policies")).put("responseLimitKb", 1).put("cleanTruncation", false);
            r.path("tools").forEach(t -> ((ObjectNode) t).putNull("outputSchema"));
        });
        target.respond(MockTarget.Response.json(200, "{\"data\":[" + "{\"id\":\"c-1\",\"name\":\"a long enough name\"},".repeat(100) + "{}]}"));

        McpSchema.CallToolResult result = list(server);

        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isNull();
    }

    @Test
    void anAnswerThatDiffersFromTheSpecIsLoggedNotRejected() throws Exception {
        var constructor = Class.forName("ro.bitboy.mcpgp.runtime.mcp.LenientOutputValidator").getDeclaredConstructor();
        constructor.setAccessible(true);
        JsonSchemaValidator validator = (JsonSchemaValidator) constructor.newInstance();
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("count", Map.of("type", "integer")));

        JsonSchemaValidator.ValidationResponse response = validator.validate(schema, Map.of("count", "twelve", "extra", List.of(1)));

        assertThat(response.valid()).isTrue();
        assertThat(response.jsonStructuredOutput()).contains("twelve");
    }
}
