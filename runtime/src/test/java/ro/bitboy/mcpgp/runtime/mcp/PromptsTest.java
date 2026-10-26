package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import ro.bitboy.mcpgp.schemas.Schemas;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Prompts with completion (tools-contract §14.2) and progress notifications (§14.3), over HTTP. */
class PromptsTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PROMPTS = """
            prompts:
              - name: refund_review
                description: Review an order before refunding it
                arguments:
                  - { name: order_id, description: Order to review, required: true }
                  - { name: tone, enum: [brief, detailed, formal] }
                messages:
                  - role: user
                    content: "Look up order {{order_id}} and tell me whether a refund is allowed. Answer {{ tone }}."
            """;

    @TempDir
    Path dir;
    MockTarget target;
    ConfigurableApplicationContext context;
    int port;
    final AtomicInteger id = new AtomicInteger(1);
    /** One client for the whole test: a client per call leaves hundreds of selector threads and pipes open. */
    final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        http.close();
        if (context != null) {
            context.close();
        }
        if (target != null) {
            target.close();
        }
    }

    Compiler.Result compile(String prompts) throws Exception {
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
                %s
                """.formatted(prompts));
        return new Compiler(new Schemas()).compile(project.resolve("orders.mcpgp.yaml"), Compiler.Options.defaults());
    }

    void start() throws Exception {
        Compiler.Result result = compile(PROMPTS);
        assertThat(result.manifest()).as("%s", result.diagnostics()).isPresent();
        Path manifest = dir.resolve("manifest.json");
        Files.writeString(manifest, CanonicalJson.pretty(result.manifest().get()));
        target = new MockTarget();
        try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        Path config = dir.resolve("orders-mcp.yaml");
        Files.writeString(config, "server: { transport: http, port: %d }\ninstance: { baseUrl: \"%s\" }\n".formatted(port, target.address()));
        Startup startup = Startup.load(new String[] {"--manifest", manifest.toString(), "--config", config.toString(),
                "--storage.root", dir.resolve("data").toString()}, Map.of(), dir);
        context = McpgpServer.start(startup, new McpgpServer.Stdio(System.in, System.out, () -> { }));
    }

    @Test
    void theCompilerNamesPromptsAndChecksTheirPlaceholders() throws Exception {
        JsonNode prompts = compile(PROMPTS).manifest().orElseThrow().path("prompts");
        assertThat(prompts.get(0).path("name").asText()).isEqualTo("orders_refund_review");
        Compiler.Result broken = compile(PROMPTS.replace("{{ tone }}", "{{ mood }}"));
        assertThat(broken.manifest()).isEmpty();
        assertThat(broken.diagnostics()).filteredOn(d -> d.level() == Diagnostic.Level.ERROR).extracting(Diagnostic::code)
                .contains("PROMPT_UNKNOWN_ARGUMENT");
    }

    @Test
    void clientsListGetAndCompletePrompts() throws Exception {
        start();
        String session = session();
        JsonNode list = message(rpc(session, "prompts/list", Map.of()));
        assertThat(list.at("/result/prompts/0/name").asText()).isEqualTo("orders_refund_review");
        assertThat(list.at("/result/prompts/0/arguments").findValuesAsText("name")).containsExactly("order_id", "tone");

        JsonNode got = message(rpc(session, "prompts/get", Map.of("name", "orders_refund_review", "arguments", Map.of("order_id", "o-42", "tone", "brief"))));
        assertThat(got.at("/result/messages/0/content/text").asText())
                .isEqualTo("Look up order o-42 and tell me whether a refund is allowed. Answer brief.");
        JsonNode optional = message(rpc(session, "prompts/get", Map.of("name", "orders_refund_review", "arguments", Map.of("order_id", "o-1"))));
        assertThat(optional.at("/result/messages/0/content/text").asText()).endsWith("Answer .");

        JsonNode missing = message(rpc(session, "prompts/get", Map.of("name", "orders_refund_review", "arguments", Map.of())));
        assertThat(missing.at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(missing.at("/error/message").asText()).contains("order_id");
        JsonNode badEnum = message(rpc(session, "prompts/get", Map.of("name", "orders_refund_review", "arguments", Map.of("order_id", "o", "tone", "loud"))));
        assertThat(badEnum.at("/error/code").asInt()).isEqualTo(-32602);

        JsonNode completion = message(rpc(session, "completion/complete", Map.of("ref", Map.of("type", "ref/prompt", "name", "orders_refund_review"),
                "argument", Map.of("name", "tone", "value", "f"))));
        assertThat(completion.at("/result/completion/values")).extracting(JsonNode::asText).containsExactly("formal");
    }

    /**
     * Regression: a handler that throws must still get its whole error reply out. With handlers offloaded to
     * boundedElastic, Reactor could interrupt the worker while it wrote the reply; Tomcat then failed the write,
     * the response ended without its last chunk and the SDK dropped the session.
     */
    @Test
    void errorRepliesSurviveConcurrentCalls() throws Exception {
        start();
        String session = session();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<JsonNode>> replies = new ArrayList<>();
            for (int i = 0; i < 800; i++) {
                replies.add(pool.submit(() -> message(rpc(session, "prompts/get",
                        Map.of("name", "orders_refund_review", "arguments", Map.of("order_id", "o", "tone", "loud"))))));
            }
            for (Future<JsonNode> reply : replies) {
                assertThat(reply.get().at("/error/code").asInt()).as("%s", reply.get()).isEqualTo(-32602);
            }
        } finally {
            pool.shutdownNow();
        }
        // the session is still there
        assertThat(message(rpc(session, "prompts/list", Map.of())).at("/result/prompts/0/name").asText()).isEqualTo("orders_refund_review");
    }

    @Test
    void slowCallsReportProgressWhenAsked() throws Exception {
        start();
        String session = session();
        target.respond(new MockTarget.Response(200, Map.of("Content-Type", "application/json"), "[]".getBytes(), 2_300));
        HttpResponse<String> slow = rpc(session, "tools/call", Map.of("name", "orders_account_list", "arguments", Map.of(),
                "_meta", Map.of("progressToken", "p-1")));
        List<JsonNode> messages = new ArrayList<>();
        for (String line : slow.body().split("\n")) {
            if (line.startsWith("data:")) {
                messages.add(JSON.readTree(line.substring(5).strip()));
            }
        }
        List<JsonNode> progress = messages.stream().filter(m -> m.path("method").asText().equals("notifications/progress")).toList();
        assertThat(progress).hasSizeGreaterThanOrEqualTo(2);
        assertThat(progress.get(0).at("/params/progressToken").asText()).isEqualTo("p-1");
        assertThat(progress.get(0).at("/params/message").asText()).contains("Waiting for orders");
        assertThat(messages.getLast().path("result").isObject()).isTrue();

        // without a token: no notifications
        target.respond(new MockTarget.Response(200, Map.of("Content-Type", "application/json"), "[]".getBytes(), 1_300));
        assertThat(rpc(session, "tools/call", Map.of("name", "orders_account_list", "arguments", Map.of())).body()).doesNotContain("notifications/progress");
    }

    String session() throws Exception {
        HttpResponse<String> init = post(null, Map.of("jsonrpc", "2.0", "id", id.getAndIncrement(), "method", "initialize", "params",
                Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(), "clientInfo", Map.of("name", "t", "version", "1"))));
        assertThat(message(init).at("/result/capabilities").has("prompts")).isTrue();
        assertThat(message(init).at("/result/capabilities").has("completions")).isTrue();
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        post(session, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        return session;
    }

    HttpResponse<String> rpc(String session, String method, Object params) throws Exception {
        return post(session, Map.of("jsonrpc", "2.0", "id", id.getAndIncrement(), "method", method, "params", params));
    }

    static JsonNode message(HttpResponse<String> response) throws Exception {
        // the last JSON-RPC message (notifications may come first)
        JsonNode last = null;
        if (response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
            for (String line : response.body().split("\n")) {
                if (line.startsWith("data:")) {
                    last = JSON.readTree(line.substring(5).strip());
                }
            }
            return last;
        }
        return JSON.readTree(response.body());
    }

    HttpResponse<String> post(String session, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp")).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        return http.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
