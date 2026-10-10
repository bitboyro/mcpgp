package ro.bitboy.mcpgp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The server over stdio, driven with raw JSON-RPC lines as an MCP client would send them. */
class StdioServerTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();

    ConfigurableApplicationContext context;
    OutputStream toServer;
    BufferedReader fromServer;
    int nextId = 1;

    @AfterEach
    void stop() throws IOException {
        if (toServer != null) {
            toServer.close();
        }
        if (context != null) {
            context.close();
        }
    }

    void start(Path manifest, String config, Path dir) throws IOException {
        Path file = dir.resolve("server.yaml");
        Files.writeString(file, config);
        Startup startup = Startup.load(new String[] {"--manifest", manifest.toString(), "--config", file.toString(),
                "--storage.root", dir.resolve("data").toString()}, Map.of("P", "secret-pass"), dir);
        PipedInputStream serverIn = new PipedInputStream(1 << 16);
        toServer = new PipedOutputStream(serverIn);
        PipedOutputStream serverOut = new PipedOutputStream();
        fromServer = new BufferedReader(new InputStreamReader(new PipedInputStream(serverOut, 1 << 20), StandardCharsets.UTF_8));
        context = McpgpServer.start(startup, new McpgpServer.Stdio(serverIn, serverOut, () -> { }));
    }

    JsonNode request(String method, Object params) throws Exception {
        int id = nextId++;
        send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        while (true) {
            String line = CompletableFuture.supplyAsync(() -> {
                try {
                    return fromServer.readLine();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).get(10, TimeUnit.SECONDS);
            JsonNode message = JSON.readTree(line);
            if (message.path("id").asInt(-1) == id) {
                return message;
            }
        }
    }

    void send(Object message) throws IOException {
        toServer.write((JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
        toServer.flush();
    }

    JsonNode initialize(String revision) throws Exception {
        JsonNode response = request("initialize", Map.of("protocolVersion", revision, "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test", "version", "1")));
        send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        return response;
    }

    static final String MULTI = """
            instances:
              - { id: prod-eu, baseUrl: orders-eu.example, auth: { type: basic, username: u, password: "${env:P}" } }
              - { id: partner-x, baseUrl: orders.partner.example, contract: "1.0", readOnly: true }
            """;

    @Test
    void servesTheHybridToolListWithTheInstanceEnumFilled(@TempDir Path dir) throws Exception {
        start(REPO.resolve("examples/manifest/orders-mcp.manifest.json"), MULTI, dir);
        JsonNode init = initialize("2025-06-18");
        assertThat(init.at("/result/protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(init.at("/result/serverInfo/name").asText()).isEqualTo("orders-mcp");
        assertThat(init.at("/result/serverInfo/version").asText()).isEqualTo("2.0.0+3");
        assertThat(init.at("/result/instructions").asText()).contains("call instances_list");

        JsonNode tools = request("tools/list", Map.of()).at("/result/tools");
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        // hybrid: pinned API tools + built-in and pack tools
        assertThat(names).contains("orders_customer_get", "orders_customer_list", "orders_order_refund", "orders_order_search",
                "instances_list", "search_tools", "describe_tool", "call_tool", "orders_audit_list")
                .doesNotContain("orders_customer_create", "orders_account_list");
        JsonNode get = tools.findParents("name").stream().filter(t -> t.path("name").asText().equals("orders_customer_get")).findFirst().orElseThrow();
        assertThat(get.at("/inputSchema/properties/instance/enum")).extracting(JsonNode::asText).containsExactly("prod-eu", "partner-x");
        assertThat(get.at("/inputSchema/properties/instance").has("x-mcpgp-instanceEnum")).isFalse();
        assertThat(get.at("/annotations/readOnlyHint").asBoolean()).isTrue();
    }

    @Test
    void instancesListShowsContractsAndAccess(@TempDir Path dir) throws Exception {
        start(REPO.resolve("examples/manifest/orders-mcp.manifest.json"), MULTI, dir);
        initialize("2025-11-25");
        JsonNode result = request("tools/call", Map.of("name", "instances_list", "arguments", Map.of())).path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode instances = result.at("/structuredContent/instances");
        assertThat(instances.get(0).path("name").asText()).isEqualTo("prod-eu");
        assertThat(instances.get(0).path("contract").asText()).isEqualTo("2.0");
        assertThat(instances.get(0).path("contractSelection").asText()).isEqualTo("auto");
        assertThat(instances.get(1).path("contract").asText()).isEqualTo("1.0");
        assertThat(instances.get(1).path("readOnly").asBoolean()).isTrue();
        assertThat(instances.get(1).path("tools").asInt()).isLessThan(instances.get(0).path("tools").asInt());
        assertThat(result.toString()).doesNotContain("secret-pass");
    }

    @Test
    void offersOnlyTheManifestsRevisions(@TempDir Path dir) throws Exception {
        start(REPO.resolve("examples/manifest/orders-mcp.manifest.json"), MULTI, dir);
        // 2024-11-05 is not in the manifest: the server answers with its newest revision
        assertThat(initialize("2024-11-05").at("/result/protocolVersion").asText()).isEqualTo("2025-11-25");
    }

    @Test
    void singleInstanceToolsHaveNoInstanceParameter(@TempDir Path dir) throws Exception {
        start(REPO.resolve("conformance/cases/content-types/multipart-split/expected/manifest.json"),
                "instance: { baseUrl: orders.example }\n", dir);
        initialize("2025-11-25");
        JsonNode tools = request("tools/list", Map.of()).at("/result/tools");
        assertThat(tools).extracting(t -> t.path("name").asText()).containsExactly("orders_customer_create", "orders_customer_create_multipart");
        assertThat(tools.get(0).at("/inputSchema/properties").has("instance")).isFalse();
    }
}
