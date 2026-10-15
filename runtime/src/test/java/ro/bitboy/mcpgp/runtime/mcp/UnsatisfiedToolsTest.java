package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.call.MockTarget;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Tools the instances' credentials can't call (auth.md §2.2): hidden or explained, and the credential that fits is used. */
class UnsatisfiedToolsTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;
    MockTarget target;

    @AfterEach
    void stop() {
        if (target != null) {
            target.close();
        }
    }

    /** The orders example with customer_create needing oauth with orders.write, and customer_get basic only. */
    McpServerFactory server(String unsatisfiedTools) throws Exception {
        target = new MockTarget();
        ObjectNode root = (ObjectNode) JSON.readTree(REPO.resolve("examples/manifest/orders-mcp.manifest.json").toFile());
        ((ObjectNode) root.at("/apis/0/downstreamAuth")).put("unsatisfiedTools", unsatisfiedTools);
        for (JsonNode tool : root.path("tools")) {
            String security = switch (tool.path("name").asText()) {
                case "orders_customer_create" -> "[[{\"scheme\": \"oauth\", \"scopes\": [\"orders.write\"]}]]";
                case "orders_customer_get" -> "[[{\"scheme\": \"basicAuth\"}]]";
                default -> null;
            };
            if (security != null) {
                tool.path("variants").forEach(v -> {
                    try {
                        ((ObjectNode) v).set("security", JSON.readTree(security));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
        }
        Manifest manifest = Manifest.of(root);
        Path file = dir.resolve("orders-mcp.yaml");
        Files.writeString(file, """
                instances:
                  - id: prod
                    baseUrl: "%1$s"
                    contract: "2.0"
                    auth: { type: oauth2ClientCredentials, tokenUrl: "%1$s/token", clientId: c, clientSecret: "${env:SECRET}", scopes: [orders.read] }
                  - id: partner
                    baseUrl: "%1$s"
                    contract: "2.0"
                    auth: { type: basic, username: u, password: "${env:SECRET}" }
                  - id: both
                    baseUrl: "%1$s"
                    contract: "2.0"
                    auth:
                      - { type: oauth2ClientCredentials, tokenUrl: "%1$s/token", clientId: c, clientSecret: "${env:SECRET}", scopes: [orders.read] }
                      - { type: basic, username: u, password: "${env:SECRET}" }
                """.formatted(target.address()));
        RuntimeConfig config = new ConfigLoader(new Schemas()).load(manifest, new ConfigLoader.Options(Optional.of(file), dir,
                Map.of("SECRET", "s3cret-value"), Map.of("storage.root", dir.resolve("data").toString())));
        return new McpServerFactory(manifest, config);
    }

    static McpSchema.CallToolResult get(McpServerFactory server, String instance) {
        return server.call(null, new McpSchema.CallToolRequest("orders_customer_get", Map.of("instance", instance, "path", Map.of("id", "c-1")), null));
    }

    @Test
    void toolsNoInstanceCanCallAreHiddenAndCallsUseTheCredentialThatFits() throws Exception {
        McpServerFactory server = server("hide");
        // no instance has orders.write: hidden, and the log says why
        assertThat(server.catalog().tool("orders_customer_create")).isEmpty();
        assertThat(server.catalog().hidden().get("orders_customer_create").toString()).contains("orders.write");
        assertThat(server.catalog().tool("orders_customer_get")).isPresent();

        // basic only: prod can't, and says what's missing, without calling
        McpSchema.CallToolResult prod = get(server, "prod");
        assertThat(prod.isError()).isTrue();
        assertThat(prod.content().toString()).contains("AUTH_DOWNSTREAM").contains("basicAuth");
        assertThat(target.received).isEmpty();

        assertThat(get(server, "partner").isError()).isFalse();
        assertThat(target.last().header("Authorization")).startsWith("Basic ");

        // the first credential doesn't fit this operation, the second does
        assertThat(get(server, "both").isError()).isFalse();
        assertThat(target.last().header("Authorization")).startsWith("Basic ");
        assertThat(target.received).noneMatch(r -> r.path().equals("/token"));
    }

    @Test
    void withErrorTheToolsStayAndCallsSayWhy() throws Exception {
        McpServerFactory server = server("error");
        assertThat(server.catalog().tool("orders_customer_create")).isPresent();
        assertThat(server.catalog().hidden()).isEmpty();
        McpSchema.CallToolResult create = server.call(null, new McpSchema.CallToolRequest("orders_customer_create",
                Map.of("instance", "partner", "body", Map.of("name", "n", "email", "a@b.c", "accountId", "a-1")), null));
        assertThat(create.isError()).isTrue();
        assertThat(create.content().toString()).contains("AUTH_DOWNSTREAM").contains("orders.write");
    }
}
