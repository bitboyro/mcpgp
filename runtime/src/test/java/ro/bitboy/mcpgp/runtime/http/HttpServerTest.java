package ro.bitboy.mcpgp.runtime.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import ro.bitboy.mcpgp.runtime.McpgpServer;
import ro.bitboy.mcpgp.runtime.Startup;
import ro.bitboy.mcpgp.runtime.call.MockTarget;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The server over Streamable HTTP: inbound auth, Origin check, health, management port. */
class HttpServerTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path dir;
    ConfigurableApplicationContext context;
    MockTarget target;
    int port;

    @AfterEach
    void stop() {
        if (context != null) {
            context.close();
        }
        if (target != null) {
            target.close();
        }
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    void start(String server) throws IOException {
        target = new MockTarget();
        port = freePort();
        Path config = dir.resolve("orders-mcp.yaml");
        Files.writeString(config, """
                server:
                  transport: http
                  port: %d
                %s
                instances:
                  - { id: prod-eu, baseUrl: "%s" }
                  - { id: offline, baseUrl: "http://127.0.0.1:1" }
                """.formatted(port, server.indent(2).stripTrailing(), target.address()));
        Startup startup = Startup.load(new String[] {"--manifest", REPO.resolve("examples/manifest/orders-mcp.manifest.json").toString(),
                "--config", config.toString(), "--storage.root", dir.resolve("data").toString()}, Map.of("KEY", "k-team-a-0123456789"), dir);
        context = McpgpServer.start(startup, new McpgpServer.Stdio(System.in, System.out, () -> { }));
    }

    HttpResponse<String> post(String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The JSON-RPC message of a JSON or SSE response. */
    static JsonNode message(HttpResponse<String> response) throws IOException {
        String body = response.body();
        if (response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
            // notifications (progress, log messages) may come first; the answer has a result or an error
            JsonNode first = null;
            for (String line : body.split("\n")) {
                if (line.startsWith("data:")) {
                    JsonNode data = JSON.readTree(line.substring(5).strip());
                    if (data.has("result") || data.has("error")) {
                        return data;
                    }
                    first = first == null ? data : first;
                }
            }
            if (first != null) {
                return first;
            }
        }
        return JSON.readTree(body);
    }

    static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""";

    /** initialize + initialized; returns the session id. */
    String session(Map<String, String> headers) throws Exception {
        HttpResponse<String> init = post(INITIALIZE, headers);
        assertThat(init.statusCode()).isEqualTo(200);
        assertThat(message(init).at("/result/serverInfo/name").asText()).isEqualTo("orders-mcp");
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        Map<String, String> withSession = new java.util.HashMap<>(headers);
        withSession.put("Mcp-Session-Id", session);
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", withSession);
        return session;
    }

    @Test
    void servesToolsOverStreamableHttpOnLoopback() throws Exception {
        start("");
        String session = session(Map.of());
        Map<String, String> headers = Map.of("Mcp-Session-Id", session, "Mcp-Protocol-Version", "2025-11-25");
        JsonNode tools = message(post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}", headers));
        assertThat(tools.at("/result/tools").findValuesAsText("name")).contains("orders_customer_get", "instances_list");

        target.respond(MockTarget.Response.json(200, "{\"id\":\"c-1\"}"));
        JsonNode call = message(post("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"orders_customer_get",
                 "arguments":{"instance":"prod-eu","path":{"id":"c-1"}}}}""", headers));
        assertThat(call.at("/result/isError").asBoolean()).isFalse();
        assertThat(target.last().path()).isEqualTo("/api/v2/customers/c-1");
    }

    @Test
    void anExistingHttpSessionHonorsLivePolicyRevocationAndRestoration() throws Exception {
        start("");
        String session = session(Map.of());
        Map<String, String> headers = Map.of("Mcp-Session-Id", session, "Mcp-Protocol-Version", "2025-11-25");
        Path file = dir.resolve("orders-mcp.yaml");
        String yaml = "server: {transport: http, port: " + port + "}\ninstances: [{id: prod-eu, baseUrl: '" + target.address() + "', contract: '2.0'}]\n";
        Files.writeString(file, yaml + "tools: {allow: []}\n");
        assertThat(context.getBean(ro.bitboy.mcpgp.runtime.Reloader.class).reload("test")).isTrue();
        target.received.clear();
        JsonNode tools = message(post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}", headers));
        assertThat(tools.at("/result/tools").findValuesAsText("name")).doesNotContain("orders_customer_get");
        JsonNode denied = message(post("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"call_tool",
                 "arguments":{"name":"orders_customer_get","instance":"prod-eu","arguments":{"path":{"id":"c-1"}}}}}
                """, headers));
        assertThat(denied.at("/result/isError").asBoolean()).isTrue();
        assertThat(target.received).isEmpty();
        Files.writeString(file, yaml);
        assertThat(context.getBean(ro.bitboy.mcpgp.runtime.Reloader.class).reload("test")).isTrue();
        JsonNode allowed = message(post("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"orders_customer_get",
                 "arguments":{"instance":"prod-eu","path":{"id":"c-1"}}}}
                """, headers));
        assertThat(allowed.at("/result/isError").asBoolean()).isFalse();
        assertThat(target.received).hasSize(1);
    }

    @Test
    void staticKeysAreRequiredAndNamedInTheAudit() throws Exception {
        start("auth: { mode: static, keys: [ { name: team-a, value: \"${env:KEY}\" } ] }");
        HttpResponse<String> none = post(INITIALIZE, Map.of());
        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.headers().firstValue("WWW-Authenticate").orElse("")).startsWith("Bearer");
        assertThat(post(INITIALIZE, Map.of("Authorization", "Bearer wrong")).statusCode()).isEqualTo(401);

        Map<String, String> auth = Map.of("Authorization", "Bearer k-team-a-0123456789");
        String session = session(auth);
        post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"instances_list","arguments":{}}}""",
                Map.of("Authorization", "Bearer k-team-a-0123456789", "Mcp-Session-Id", session, "Mcp-Protocol-Version", "2025-11-25"));
        List<String> lines = Files.readAllLines(dir.resolve("data/audit/audit.jsonl"));
        JsonNode call = lines.stream().map(l -> {
            try {
                return JSON.readTree(l);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).filter(e -> e.path("type").asText().equals("tool.call")).findFirst().orElseThrow();
        assertThat(call.at("/actor/id").asText()).isEqualTo("team-a");
        assertThat(call.at("/actor/auth").asText()).isEqualTo("static");
        assertThat(String.join("\n", lines)).contains("\"type\":\"auth.inbound\"").doesNotContain("k-team-a-0123456789");
        assertThat(lines).anyMatch(l -> l.contains("\"type\":\"session.start\"") && l.contains("\"id\":\"team-a\""));
    }

    @Test
    void foreignOriginsAreRefused() throws Exception {
        start("allowedOrigins: [ \"https://app.example\" ]");
        assertThat(post(INITIALIZE, Map.of("Origin", "https://evil.example")).statusCode()).isEqualTo(403);
        assertThat(post(INITIALIZE, Map.of("Origin", "https://app.example")).statusCode()).isEqualTo(200);
    }

    @Test
    void healthReportsInstances() throws Exception {
        start("");
        HttpResponse<String> live = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health/live")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(JSON.readTree(live.body()).path("status").asText()).isEqualTo("up");
        HttpResponse<String> ready = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health/ready")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ready.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(ready.body());
        assertThat(body.path("status").asText()).isEqualTo("degraded");
        assertThat(body.at("/instances/prod-eu").asText()).isEqualTo("up");
        assertThat(body.at("/instances/offline").asText()).isEqualTo("down");
    }

    @Test
    void managementPortSeparatesHealthFromMcp() throws Exception {
        int management = freePort();
        start("management: { port: " + management + " }");
        HttpResponse<String> onManagement = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + management + "/health/live")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(onManagement.statusCode()).isEqualTo(200);
        HttpResponse<String> onMain = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health/live")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(onMain.statusCode()).isEqualTo(404);
        HttpResponse<String> mcpOnManagement = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + management + "/mcp"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(INITIALIZE)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(mcpOnManagement.statusCode()).isEqualTo(404);
    }
}
