package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.manifest.CanonicalJson;
import ro.bitboy.mcpgp.runtime.McpgpServer;
import ro.bitboy.mcpgp.runtime.Startup;
import ro.bitboy.mcpgp.runtime.call.MockTarget;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code toolsets} exposure (tools-contract §15.3), from the compiled manifest to each session's tool list. */
class ToolsetsTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;
    MockTarget target;
    ConfigurableApplicationContext context;

    @AfterEach
    void stop() {
        if (context != null) {
            context.close();
        }
        if (target != null) {
            target.close();
        }
    }

    /** The orders 2.0 contract with toolsets exposure; {@code toolsets} as YAML, or empty for one per tag. */
    Path compile(String toolsets) throws Exception {
        Path project = dir.resolve("project-" + System.nanoTime());
        Files.createDirectories(project.resolve("contracts"));
        Files.copy(REPO.resolve("examples/platform/orders/contracts/orders-2.0.yaml"), project.resolve("contracts/orders-2.0.yaml"));
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
                exposure:
                  mode: toolsets
                %s
                """.formatted(toolsets.indent(2).stripTrailing()));
        Compiler.Result result = new Compiler(new Schemas()).compile(project.resolve("orders.mcpgp.yaml"), Compiler.Options.defaults());
        assertThat(result.manifest()).as("%s", result.diagnostics()).isPresent();
        Path manifest = project.resolve("manifest.json");
        Files.writeString(manifest, CanonicalJson.pretty(result.manifest().get()));
        return manifest;
    }

    McpServerFactory server(Path manifestFile) throws Exception {
        target = new MockTarget();
        Path config = dir.resolve("orders-mcp.yaml");
        Files.writeString(config, "instance: { baseUrl: \"" + target.address() + "\" }\n");
        Manifest manifest = Manifest.file(manifestFile);
        RuntimeConfig runtime = new ConfigLoader(new Schemas()).load(manifest, new ConfigLoader.Options(Optional.of(config), dir, Map.of(),
                Map.of("storage.root", dir.resolve("data").toString())));
        return new McpServerFactory(manifest, runtime);
    }

    static McpSchema.CallToolResult call(McpServerFactory server, String tool, Map<String, Object> arguments) {
        return server.call(null, new McpSchema.CallToolRequest(tool, arguments, null));
    }

    static JsonNode structured(McpSchema.CallToolResult result) {
        assertThat(result.isError()).as("%s", result.content()).isFalse();
        return JSON.valueToTree(result.structuredContent());
    }

    @Test
    void theCompilerGroupsToolsByTagOrBySelector() throws Exception {
        JsonNode byTag = JSON.readTree(compile("").toFile()).at("/exposure/toolsets");
        assertThat(byTag.properties()).extracting(Map.Entry::getKey).containsExactly("accounts", "customer-contacts", "customers", "invoices", "orders");
        assertThat(byTag.at("/customers/tools")).extracting(JsonNode::asText).contains("orders_customer_list", "orders_customer_create");
        assertThat(byTag.at("/customers/description").asText()).isEqualTo("Operations tagged customers");

        JsonNode defined = JSON.readTree(compile("""
                toolsets:
                  sales: { description: Orders and invoices, match: { tag: [orders, invoices] } }
                  readers: { description: What only reads, match: { method: GET } }
                """).toFile()).at("/exposure/toolsets");
        assertThat(defined.at("/sales/tools")).extracting(JsonNode::asText).contains("orders_order_search", "orders_overdue_invoice_list")
                .doesNotContain("orders_customer_list");
        assertThat(defined.at("/readers/tools")).extracting(JsonNode::asText).contains("orders_customer_list").doesNotContain("orders_customer_create");
        assertThat(defined.at("/sales/description").asText()).isEqualTo("Orders and invoices");
    }

    @Test
    void aSessionEnablesAToolsetAndCanThenCallItsTools() throws Exception {
        McpServerFactory server = server(compile(""));
        assertThat(server.catalog().listed()).extracting(t -> t.path("name").asText())
                .contains("toolsets_list", "toolset_enable", "toolset_disable").noneMatch(n -> n.startsWith("orders_account"));
        List<String> changed = new ArrayList<>();
        server.toolsets().orElseThrow().onListChanged(changed::add);

        JsonNode list = structured(call(server, "toolsets_list", Map.of()));
        assertThat(list.path("toolsets").findValuesAsText("name")).contains("customers", "orders");
        assertThat(list.at("/toolsets/0/enabled").asBoolean()).isFalse();

        McpSchema.CallToolResult blocked = call(server, "orders_account_list", Map.of());
        assertThat(blocked.isError()).isTrue();
        assertThat(JSON.valueToTree(blocked.structuredContent()).path("code").asText()).isEqualTo("TOOLSET_DISABLED");
        assertThat(target.received).isEmpty();

        assertThat(structured(call(server, "toolset_enable", Map.of("name", "accounts"))).path("tools").asInt()).isEqualTo(1);
        assertThat(changed).hasSize(1);
        target.respond(MockTarget.Response.json(200, "[]"));
        structured(call(server, "orders_account_list", Map.of()));
        assertThat(server.toolsets().orElseThrow().visible(null)).contains("orders_account_list").doesNotContain("orders_order_search");

        structured(call(server, "toolset_enable", Map.of("name", "accounts")));       // already on: no notification
        assertThat(changed).hasSize(1);
        structured(call(server, "toolset_disable", Map.of("name", "accounts")));
        assertThat(changed).hasSize(2);
        assertThat(call(server, "orders_account_list", Map.of()).isError()).isTrue();
        assertThat(call(server, "toolset_enable", Map.of("name", "nothing")).isError()).isTrue();
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    @Test
    void overHttpEachSessionHasItsOwnToolList() throws Exception {
        Path manifest = compile("");
        target = new MockTarget();
        int port = freePort();
        Path config = dir.resolve("orders-mcp.yaml");
        Files.writeString(config, """
                server: { transport: http, port: %d }
                instance: { baseUrl: "%s" }
                """.formatted(port, target.address()));
        Startup startup = Startup.load(new String[] {"--manifest", manifest.toString(), "--config", config.toString(),
                "--storage.root", dir.resolve("data").toString()}, Map.of(), dir);
        context = McpgpServer.start(startup, new McpgpServer.Stdio(System.in, System.out, () -> { }));
        String a = session(port);
        String b = session(port);
        rpc(port, a, "tools/call", Map.of("name", "toolset_enable", "arguments", Map.of("name", "orders")));
        List<String> forA = rpc(port, a, "tools/list", Map.of()).at("/result/tools").findValuesAsText("name");
        List<String> forB = rpc(port, b, "tools/list", Map.of()).at("/result/tools").findValuesAsText("name");
        assertThat(forA).contains("orders_order_search", "toolsets_list");
        assertThat(forB).contains("toolsets_list").doesNotContain("orders_order_search");
    }

    static final HttpClient HTTP = HttpClient.newHttpClient();
    int id = 1;

    String session(int port) throws Exception {
        HttpResponse<String> init = post(port, null, Map.of("jsonrpc", "2.0", "id", id++, "method", "initialize", "params",
                Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(), "clientInfo", Map.of("name", "t", "version", "1"))));
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        post(port, session, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        return session;
    }

    JsonNode rpc(int port, String session, String method, Object params) throws Exception {
        return ro.bitboy.mcpgp.runtime.http.HttpServerTestAccess.message(post(port, session, Map.of("jsonrpc", "2.0", "id", id++, "method", method,
                "params", params)));
    }

    static HttpResponse<String> post(int port, String session, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp")).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
