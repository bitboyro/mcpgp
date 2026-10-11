package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GET operations as MCP resources (tools-contract §14.1), from the rule to resources/read. */
class ResourcesTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();
    static final String RULES = """
            - match: { operationId: "~^(getCustomerAccountByIdV2|listAccounts|listCustomers|getInvoiceDocument)$" }
              expose: resource
            """;

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

    Compiler.Result compile(String rules, String instances) throws Exception {
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
                instances: { mode: %s }
                overrides:
                  rules:
                %s
                """.formatted(instances, rules.indent(4).stripTrailing()));
        return new Compiler(new Schemas()).compile(project.resolve("orders.mcpgp.yaml"), Compiler.Options.defaults());
    }

    Path manifest(String instances) throws Exception {
        Compiler.Result result = compile(RULES, instances);
        assertThat(result.manifest()).as("%s", result.diagnostics()).isPresent();
        Path file = dir.resolve("manifest-" + instances + ".json");
        Files.writeString(file, CanonicalJson.pretty(result.manifest().get()));
        return file;
    }

    McpServerFactory server(Path manifestFile, String config) throws Exception {
        Path file = dir.resolve("orders-mcp.yaml");
        Files.writeString(file, config);
        Manifest manifest = Manifest.file(manifestFile);
        RuntimeConfig runtime = new ConfigLoader(new Schemas()).load(manifest, new ConfigLoader.Options(Optional.of(file), dir, Map.of(),
                Map.of("storage.root", dir.resolve("data").toString())));
        return new McpServerFactory(manifest, runtime);
    }

    @Test
    void theCompilerMakesResourcesOfGetOperationsOnly() throws Exception {
        JsonNode manifest = JSON.readTree(manifest("single").toFile());
        assertThat(manifest.at("/resources/uriScheme").asText()).isEqualTo("api");
        assertThat(manifest.at("/resources/items").findValuesAsText("uriTemplate"))
                .containsExactlyInAnyOrder("customers/{id}{?expand}", "accounts", "customers{?accountId,limit,cursor}", "invoices/{id}/document");
        List<String> resourceNames = new java.util.ArrayList<>();
        manifest.at("/resources/items").forEach(r -> resourceNames.add(r.path("name").asText()));
        assertThat(resourceNames).contains("orders_account_list", "orders_customer_list");
        List<String> toolNames = new java.util.ArrayList<>();
        manifest.path("tools").forEach(t -> toolNames.add(t.path("name").asText()));
        assertThat(toolNames).doesNotContainAnyElementsOf(resourceNames).contains("orders_customer_create");
        assertThat(manifest.at("/resources/items").findValuesAsText("mimeType")).contains("application/json", "application/pdf");

        Compiler.Result refund = compile("- { match: { operationId: refundOrder }, expose: resource }", "single");
        assertThat(refund.manifest()).isEmpty();
        assertThat(refund.diagnostics()).filteredOn(d -> d.level() == Diagnostic.Level.ERROR).extracting(Diagnostic::code).contains("RESOURCE_NOT_GET");
    }

    @Test
    void aReadIsTheOperationsCall() throws Exception {
        target = new MockTarget();
        McpServerFactory server = server(manifest("single"), "instance: { baseUrl: \"" + target.address() + "\" }\n");

        target.respond(MockTarget.Response.json(200, "{\"id\":\"c-7\",\"name\":\"Ada\"}"));
        McpSchema.ReadResourceResult customer = server.read(null, new McpSchema.ReadResourceRequest("api://customers/c-7?expand=orders&debug=1"));
        assertThat(target.last().path()).isEqualTo("/api/v2/customers/c-7");
        assertThat(target.last().query()).isEqualTo("expand=orders");            // debug isn't declared: dropped
        McpSchema.TextResourceContents text = (McpSchema.TextResourceContents) customer.contents().get(0);
        assertThat(text.uri()).isEqualTo("api://customers/c-7?expand=orders&debug=1");
        assertThat(JSON.readTree(text.text()).path("name").asText()).isEqualTo("Ada");

        target.respond(MockTarget.Response.json(200, "[]"));
        server.read(null, new McpSchema.ReadResourceRequest("api://customers?accountId=a-1&limit=20"));
        assertThat(target.last().query()).contains("accountId=a-1").contains("limit=20");

        target.respond(new MockTarget.Response(200, Map.of("Content-Type", "application/pdf"), new byte[] {'%', 'P', 'D', 'F'}, 0));
        McpSchema.ReadResourceResult document = server.read(null, new McpSchema.ReadResourceRequest("api://invoices/i-1/document"));
        McpSchema.BlobResourceContents blob = (McpSchema.BlobResourceContents) document.contents().get(0);
        assertThat(blob.mimeType()).isEqualTo("application/pdf");
        assertThat(blob.blob()).isEqualTo("JVBERg==");

        assertThatThrownBy(() -> server.read(null, new McpSchema.ReadResourceRequest("api://nowhere/1"))).isInstanceOf(McpError.class)
                .hasMessageContaining("not found");
        // reads are audited like tool calls
        Path audit = Files.walk(dir.resolve("data")).filter(p -> p.toString().endsWith(".jsonl")).findFirst().orElseThrow();
        assertThat(Files.readString(audit)).contains("\"type\":\"resource.read\"").contains("\"tool\":\"orders_customer_list\"");
    }

    @Test
    void multiInstanceUrisNameTheInstance() throws Exception {
        target = new MockTarget();
        McpServerFactory server = server(manifest("multi"), """
                instances:
                  - { id: prod-eu, baseUrl: "%s" }
                  - { id: test, baseUrl: "%1$s" }
                """.formatted(target.address()));
        target.respond(MockTarget.Response.json(200, "[]"));
        server.read(null, new McpSchema.ReadResourceRequest("api://test/accounts"));
        assertThat(target.last().path()).isEqualTo("/api/v2/accounts");
        assertThatThrownBy(() -> server.read(null, new McpSchema.ReadResourceRequest("api://staging/accounts"))).isInstanceOf(McpError.class);
    }

    @Test
    void clientsListResourcesAndTemplates() throws Exception {
        target = new MockTarget();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path config = dir.resolve("http.yaml");
        Files.writeString(config, "server: { transport: http, port: %d }\ninstance: { baseUrl: \"%s\" }\n".formatted(port, target.address()));
        Startup startup = Startup.load(new String[] {"--manifest", manifest("single").toString(), "--config", config.toString(),
                "--storage.root", dir.resolve("data").toString()}, Map.of(), dir);
        context = McpgpServer.start(startup, new McpgpServer.Stdio(System.in, System.out, () -> { }));
        HttpResponse<String> init = post(port, null, Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params",
                Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(), "clientInfo", Map.of("name", "t", "version", "1"))));
        JsonNode capabilities = ro.bitboy.mcpgp.runtime.http.HttpServerTestAccess.message(init).at("/result/capabilities");
        assertThat(capabilities.has("resources")).isTrue();
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        post(port, session, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        List<String> plain = ro.bitboy.mcpgp.runtime.http.HttpServerTestAccess.message(post(port, session,
                Map.of("jsonrpc", "2.0", "id", 2, "method", "resources/list", "params", Map.of()))).at("/result/resources").findValuesAsText("uri");
        List<String> templates = ro.bitboy.mcpgp.runtime.http.HttpServerTestAccess.message(post(port, session,
                Map.of("jsonrpc", "2.0", "id", 3, "method", "resources/templates/list", "params", Map.of()))).at("/result/resourceTemplates")
                .findValuesAsText("uriTemplate");
        assertThat(plain).containsExactly("api://accounts");
        assertThat(templates).contains("api://customers/{id}{?expand}", "api://invoices/{id}/document");

        target.respond(MockTarget.Response.json(200, "{\"id\":\"c-1\"}"));
        JsonNode read = ro.bitboy.mcpgp.runtime.http.HttpServerTestAccess.message(post(port, session, Map.of("jsonrpc", "2.0", "id", 4,
                "method", "resources/read", "params", Map.of("uri", "api://customers/c-1"))));
        assertThat(read.at("/result/contents/0/text").asText()).contains("c-1");
    }

    static HttpResponse<String> post(int port, String session, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp")).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
