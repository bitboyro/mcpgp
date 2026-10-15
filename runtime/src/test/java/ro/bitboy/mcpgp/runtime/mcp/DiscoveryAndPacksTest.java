package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.TestCerts;
import ro.bitboy.mcpgp.runtime.call.MockTarget;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Discovery tools (tools-contract §15) and the P0 packs (packaging.md §4.1) on the orders example. */
class DiscoveryAndPacksTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Manifest ORDERS = Manifest.file(REPO.resolve("examples/manifest/orders-mcp.manifest.json"));
    static final ObjectMapper JSON = new ObjectMapper();
    static final TestCerts CERTS = TestCerts.get();

    @TempDir
    Path dir;
    MockTarget target;
    MockTarget https;
    MockTarget trusted;
    RuntimeConfig config;
    McpServerFactory server;

    @AfterEach
    void stop() {
        target.close();
        if (https != null) {
            https.close();
        }
        if (trusted != null) {
            trusted.close();
        }
    }

    void start(String extraInstances) throws IOException {
        target = new MockTarget();
        Path file = dir.resolve("server.yaml");
        Files.writeString(file, """
                instances:
                  - { id: prod-eu, baseUrl: "%s" }
                  - { id: partner-x, baseUrl: "%1$s", contract: "1.0" }
                """.formatted(target.address()) + extraInstances);
        config = new ConfigLoader(new Schemas()).load(ORDERS, new ConfigLoader.Options(Optional.of(file), dir, Map.of(),
                Map.of("storage.root", dir.resolve("data").toString())));
        server = new McpServerFactory(ORDERS, config);
    }

    McpSchema.CallToolResult call(String tool, Map<String, Object> arguments) {
        return server.call(null, new McpSchema.CallToolRequest(tool, arguments, null));
    }

    JsonNode structured(String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = call(tool, arguments);
        assertThat(result.isError()).as("%s: %s", tool, result.content()).isFalse();
        return JSON.valueToTree(result.structuredContent());
    }

    static List<String> names(JsonNode array) {
        return array.findValuesAsText("name");
    }

    @Test
    void searchRanksByRelevanceAndRespectsTheInstanceContract() throws IOException {
        start("");
        JsonNode refund = structured("search_tools", Map.of("instance", "prod-eu", "query", "refund an order"));
        assertThat(names(refund.path("tools")).get(0)).isEqualTo("orders_order_refund");
        assertThat(refund.at("/tools/0/readOnly").asBoolean()).isFalse();

        JsonNode exact = structured("search_tools", Map.of("instance", "prod-eu", "query", "orders_customer_get"));
        assertThat(names(exact.path("tools")).get(0)).isEqualTo("orders_customer_get");

        // contacts exist only in contract 2.0
        assertThat(names(structured("search_tools", Map.of("instance", "prod-eu", "query", "customer contacts")).path("tools")))
                .contains("orders_customer_contact_list");
        assertThat(names(structured("search_tools", Map.of("instance", "partner-x", "query", "customer contacts")).path("tools")))
                .doesNotContain("orders_customer_contact_list");
    }

    @Test
    void describeAndCallGoThroughTheSamePathAsDirectCalls() throws IOException {
        start("");
        JsonNode described = structured("describe_tool", Map.of("instance", "prod-eu", "name", "orders_customer_get"));
        assertThat(described.at("/inputSchema/properties/instance/enum")).hasSize(2);

        target.respond(MockTarget.Response.json(200, "{\"id\":\"c-1\"}"));
        McpSchema.CallToolResult result = call("call_tool", Map.of("instance", "prod-eu", "name", "orders_customer_get",
                "arguments", Map.of("path", Map.of("id", "c-1"))));
        assertThat(result.isError()).isFalse();
        assertThat(JSON.valueToTree(result.structuredContent()).path("id").asText()).isEqualTo("c-1");
        assertThat(target.last().path()).isEqualTo("/api/v2/customers/c-1");

        // validation applies to the inner call; built-in and pack tools are out of reach
        McpSchema.CallToolResult invalid = call("call_tool", Map.of("instance", "prod-eu", "name", "orders_customer_get",
                "arguments", Map.of("path", Map.of())));
        assertThat(JSON.valueToTree(invalid.structuredContent()).path("code").asText()).isEqualTo("INPUT_VALIDATION");
        McpSchema.CallToolResult pack = call("call_tool", Map.of("instance", "prod-eu", "name", "orders_audit_list", "arguments", Map.of()));
        assertThat(((McpSchema.TextContent) pack.content().get(0)).text()).contains("No tool named 'orders_audit_list'");

        JsonNode audit = structured("orders_audit_list", Map.of("tool", "orders_customer_get"));
        assertThat(audit.path("items")).isNotEmpty();
        assertThat(audit.at("/items/0/outcome").asText()).isEqualTo("error");       // newest first
        assertThat(audit.path("hasMore").asBoolean()).isFalse();
    }

    @Test
    void catalogStyleListsAndPagesOperations() throws IOException {
        start("");
        Discovery discovery = new Discovery(ORDERS, config, server.catalog(), server.contracts(), server::call);
        ToolHandler list = discovery.handlers().get("list_operations");
        JsonNode page = JSON.valueToTree(list.call(null, null, Map.of("tag", "customers"), new CallRecord()));
        assertThat(names(page.path("items"))).contains("orders_customer_get", "orders_customer_create_multipart")
                .doesNotContain("orders_order_refund");
        assertThat(page.at("/items/0/method").asText()).isNotEmpty();
        JsonNode schema = JSON.valueToTree(discovery.handlers().get("get_operation_schema")
                .call(null, null, Map.of("name", "orders_invoice_document_get"), new CallRecord()));
        assertThat(schema.at("/responses/0/contentType").asText()).isEqualTo("application/pdf");
    }

    @Test
    void introspectionAndSchema() throws IOException {
        start("");
        JsonNode explained = structured("orders_explain_tool", Map.of("tool", "orders_customer_get"));
        assertThat(explained.at("/contracts/1.0").asText()).isEqualTo("GET /customers/{id}");
        assertThat(explained.at("/provenance/name/0/step").asText()).isEqualTo("source");

        JsonNode info = structured("orders_manifest_info", Map.of());
        assertThat(info.at("/server/version").asText()).isEqualTo("2.0.0+3");
        assertThat(info.at("/apis/0/defaultContract").asText()).isEqualTo("2.0");

        // the orders example doesn't enable the schema pack: its handler directly
        ToolHandler describe = new Packs(ORDERS, config, server.catalog()).handlers().get("describe_schema");
        JsonNode top = JSON.valueToTree(describe.call(null, null, Map.of("tool", "orders_customer_create_multipart"), new CallRecord()));
        assertThat(top.at("/properties/body/more").asText()).contains("part=");
        JsonNode document = JSON.valueToTree(describe.call(null, null,
                Map.of("tool", "orders_customer_create_multipart", "part", "body.document"), new CallRecord()));
        assertThat(document.path("type").asText()).isEqualTo("oneOf");
        JsonNode byPath = JSON.valueToTree(describe.call(null, null,
                Map.of("tool", "orders_customer_create_multipart", "part", "body.document", "variant", 1), new CallRecord()));
        assertThat(byPath.at("/properties/path/required").asBoolean()).isTrue();
    }

    @Test
    void diagnosticsFetchCertificatesAndCheckConnectivity() throws Exception {
        https = MockTarget.https(CERTS.otherHostP12, null);
        trusted = MockTarget.https(CERTS.serverP12, null);
        start("  - { id: secure, baseUrl: \"" + trusted.address() + "\", tls: { ca: { path: " + CERTS.serverPem + " } } }\n"
                + "  - { id: odd, baseUrl: \"" + https.address() + "\" }\n");
        JsonNode cert = structured("orders_tls_certificate_fetch", Map.of("instance", "odd"));
        assertThat(cert.at("/chain/0/subject").asText()).isEqualTo("CN=other.example");
        assertThat(cert.at("/chain/0/sha256").asText()).matches("([0-9A-F]{2}:){31}[0-9A-F]{2}");
        assertThat(cert.path("hostnameMatches").asBoolean()).isFalse();
        assertThat(cert.path("warning").asText()).contains("does not cover 127.0.0.1");

        JsonNode secure = structured("orders_connectivity_check", Map.of("instance", "secure"));
        assertThat(secure.path("ok").asBoolean()).as(secure.toString()).isTrue();
        JsonNode odd = structured("orders_connectivity_check", Map.of("instance", "odd"));
        assertThat(odd.path("ok").asBoolean()).isFalse();
        assertThat(odd.at("/steps/tcp").asText()).isEqualTo("connected");
        assertThat(odd.at("/steps/tls").asText()).startsWith("failed");
        assertThat(odd.path("hint").asText()).contains("tls_certificate_fetch");
    }

    @Test
    void toolsWithoutAnImplementationAreNotOffered() throws IOException {
        start("");
        assertThat(server.catalog().listed()).extracting(t -> t.path("name").asText())
                .contains("search_tools", "orders_audit_list", "orders_explain_tool", "orders_recording_start");
        // a pack tool from a newer compiler that this runtime doesn't know
        com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode) ORDERS.root().deepCopy();
        root.withArray("tools").addObject().put("name", "orders_mystery_do").put("kind", "pack").put("pack", "mystery")
                .put("title", "Mystery").put("description", "From the future.").putObject("inputSchema").put("type", "object");
        McpServerFactory newer = new McpServerFactory(Manifest.of(root), config);
        assertThat(newer.catalog().tool("orders_mystery_do")).isEmpty();
        McpSchema.CallToolResult mystery = newer.call(null, new McpSchema.CallToolRequest("orders_mystery_do", Map.of(), null));
        assertThat(((McpSchema.TextContent) mystery.content().get(0)).text()).contains("Unknown tool");
    }
}
