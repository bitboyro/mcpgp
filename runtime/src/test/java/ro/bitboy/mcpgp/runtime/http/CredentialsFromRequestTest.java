package ro.bitboy.mcpgp.runtime.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import ro.bitboy.mcpgp.runtime.McpgpServer;
import ro.bitboy.mcpgp.runtime.Startup;
import ro.bitboy.mcpgp.runtime.call.MockTarget;
import ro.bitboy.mcpgp.runtime.config.ConfigException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Credentials for the API sent by the MCP client in a configured header (auth.md §2.4). */
class CredentialsFromRequestTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String TOKEN = "Bearer user-token-0123456789";

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

    /** The orders example with credentials from the request enabled. */
    Path manifest(boolean enabled) throws Exception {
        ObjectNode manifest = (ObjectNode) JSON.readTree(REPO.resolve("examples/manifest/orders-mcp.manifest.json").toFile());
        if (enabled) {
            ((ObjectNode) manifest.at("/apis/0/upstreamAuth")).putObject("fromRequest");
        }
        Path file = dir.resolve("orders-mcp.manifest.json");
        JSON.writeValue(file.toFile(), manifest);
        return file;
    }

    Startup load(String instances, String server, boolean enabled) throws Exception {
        if (target == null) {
            target = new MockTarget();
        }
        port = HttpServerTest.freePort();
        Path config = dir.resolve("orders-mcp.yaml");
        Files.writeString(config, """
                server:
                  transport: http
                  port: %d
                %s
                instances:
                %s
                """.formatted(port, server.indent(2).stripTrailing(), instances.formatted(target.address()).indent(2).stripTrailing()));
        return Startup.load(new String[] {"--manifest", manifest(enabled).toString(), "--config", config.toString(),
                "--storage.root", dir.resolve("data").toString()}, Map.of("KEY", "k-team-a-0123456789", "SERVICE_KEY", "service-key-0123"), dir);
    }

    void start(String instances) throws Exception {
        context = McpgpServer.start(load(instances, "", true), new McpgpServer.Stdio(System.in, System.out, () -> { }));
    }

    HttpResponse<String> post(String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    String session() throws Exception {
        HttpResponse<String> init = post(HttpServerTest.INITIALIZE, Map.of());
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", Map.of("Mcp-Session-Id", session));
        return session;
    }

    JsonNode call(String session, String instance, Map<String, String> extra) throws Exception {
        Map<String, String> headers = new HashMap<>(extra);
        headers.put("Mcp-Session-Id", session);
        headers.put("Mcp-Protocol-Version", "2025-11-25");
        return HttpServerTest.message(post("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"orders_customer_get",
                 "arguments":{"instance":"%s","path":{"id":"c-1"}}}}""".formatted(instance), headers));
    }

    @Test
    void theClientsHeaderIsSentToTheApiAndNeverKept() throws Exception {
        start("""
                - { id: prod-eu, baseUrl: "%1$s", auth: { type: fromRequest, header: X-Orders-Authorization } }
                - { id: test, baseUrl: "%1$s", auth: { type: fromRequest } }""");
        String session = session();

        // a 401 that echoes the credential: masked in what the agent sees
        target.respond(MockTarget.Response.json(401, "{\"error\":\"bad token user-token-0123456789\"}"));
        JsonNode refused = call(session, "prod-eu", Map.of("X-Orders-Authorization", TOKEN));
        assertThat(target.last().header("Authorization")).isEqualTo(TOKEN);
        assertThat(refused.toString()).doesNotContain("user-token-0123456789");

        target.respond(MockTarget.Response.json(200, "{\"id\":\"c-1\"}"));
        assertThat(call(session, "prod-eu", Map.of("X-Orders-Authorization", TOKEN)).at("/result/isError").asBoolean()).isFalse();

        // the next request without the header: nothing was kept from the one before
        JsonNode missing = call(session, "prod-eu", Map.of());
        assertThat(missing.at("/result/isError").asBoolean()).isTrue();
        assertThat(missing.toString()).contains("AUTH_UPSTREAM").contains("X-Orders-Authorization");

        // each instance reads its own header: prod's credential never reaches test
        int before = target.received.size();
        JsonNode other = call(session, "test", Map.of("X-Orders-Authorization", TOKEN));
        assertThat(other.toString()).contains("X-Orders-Mcp-Test-Authorization");
        assertThat(target.received).hasSize(before);

        String audit = Files.readString(dir.resolve("data/audit/audit.jsonl"));
        assertThat(audit).contains("\"auth\":\"fromRequest\"").doesNotContain("user-token-0123456789");
    }

    @Test
    void withoutTheHeaderTheNextCredentialIsUsed() throws Exception {
        start("""
                - id: prod-eu
                  baseUrl: "%1$s"
                  auth:
                    - { type: fromRequest, header: X-Orders-Authorization }
                    - { type: apiKey, value: "${env:SERVICE_KEY}" }""");
        String session = session();
        call(session, "prod-eu", Map.of("X-Orders-Authorization", TOKEN));
        assertThat(target.last().header("Authorization")).isEqualTo(TOKEN);
        assertThat(target.last().header("X-Api-Key")).isNull();

        call(session, "prod-eu", Map.of());
        assertThat(target.last().header("X-Api-Key")).isEqualTo("service-key-0123");
        assertThat(target.last().header("Authorization")).isNull();
    }

    @Test
    void theConfigIsCheckedAtStart() throws Exception {
        // not enabled in the definition
        assertThatThrownBy(() -> load("- { id: prod-eu, baseUrl: \"%1$s\", auth: { type: fromRequest } }", "", false))
                .isInstanceOf(ConfigException.class).hasMessageContaining("not enabled in this server's definition");
        // two instances, one header
        assertThatThrownBy(() -> load("""
                - { id: prod-eu, baseUrl: "%1$s", auth: { type: fromRequest, header: X-Token } }
                - { id: test, baseUrl: "%1$s", auth: { type: fromRequest, header: x-token } }""", "", true))
                .isInstanceOf(ConfigException.class).hasMessageContaining("give each instance its own header");
        // Authorization is the server's own sign-in here
        assertThatThrownBy(() -> load("- { id: prod-eu, baseUrl: \"%1$s\", auth: { type: fromRequest, header: Authorization } }",
                "auth: { mode: static, keys: [ { name: a, value: \"${env:KEY}\" } ] }", true))
                .isInstanceOf(ConfigException.class).hasMessageContaining("carries the client's sign-in");
        // a header HTTP or MCP uses
        assertThatThrownBy(() -> load("- { id: prod-eu, baseUrl: \"%1$s\", auth: { type: fromRequest, header: Mcp-Session-Id } }", "", true))
                .isInstanceOf(ConfigException.class).hasMessageContaining("carries something else");
    }
}
