package ro.bitboy.mcpgp.runtime.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import ro.bitboy.mcpgp.runtime.McpgpServer;
import ro.bitboy.mcpgp.runtime.Startup;
import ro.bitboy.mcpgp.runtime.call.MockTarget;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code notifications/cancelled} over Streamable HTTP: a call waiting for a slow API stops at once, the
 * API request is aborted, the audit says {@code cancelled}; a cancel that arrives too late, names an
 * unknown request, or comes from another session changes nothing.
 */
class CancellationTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final long SLOW_MS = 8000;
    static final String CALL = """
            {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"orders_customer_get",
             "arguments":{"instance":"prod-eu","path":{"id":"c-1"}}}}""";

    @TempDir
    Path dir;
    ConfigurableApplicationContext context;
    MockTarget target;
    int port;

    @BeforeEach
    void start() throws Exception {
        target = new MockTarget();
        port = HttpServerTest.freePort();
        Path config = dir.resolve("server.yaml");
        Files.writeString(config, """
                server: { transport: http, port: %d }
                instances:
                  - { id: prod-eu, baseUrl: "%s", contract: "2.0" }
                """.formatted(port, target.address()));
        Startup startup = Startup.load(new String[] {"--manifest", REPO.resolve("examples/manifest/orders-mcp.manifest.json").toString(),
                "--config", config.toString(), "--storage.root", dir.resolve("data").toString()}, Map.of(), dir);
        context = McpgpServer.start(startup, new McpgpServer.Stdio(System.in, System.out, () -> { }));
    }

    @AfterEach
    void stop() {
        context.close();
        target.close();
    }

    CompletableFuture<HttpResponse<String>> postAsync(String body, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String body, Map<String, String> headers) throws Exception {
        return postAsync(body, headers).get(20, TimeUnit.SECONDS);
    }

    Map<String, String> session() throws Exception {
        HttpResponse<String> init = post(HttpServerTest.INITIALIZE, Map.of());
        String id = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        Map<String, String> headers = Map.of("Mcp-Session-Id", id, "Mcp-Protocol-Version", "2025-11-25");
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", headers);
        return headers;
    }

    void cancel(Map<String, String> headers, int requestId, String reason) throws Exception {
        HttpResponse<String> answer = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":"
                + requestId + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}}", headers);
        assertThat(answer.statusCode()).isEqualTo(202);
    }

    void awaitUpstream() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (target.received.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(target.received).as("the call reached the API").isNotEmpty();
    }

    void slow() {
        target.respond(new MockTarget.Response(200, Map.of("Content-Type", "application/json"),
                "{\"id\":\"c-1\"}".getBytes(StandardCharsets.UTF_8), SLOW_MS));
    }

    @Test
    void cancellingStopsTheCallAtOnce() throws Exception {
        slow();
        Map<String, String> headers = session();
        long start = System.nanoTime();
        CompletableFuture<HttpResponse<String>> call = postAsync(CALL.formatted(7), headers);
        awaitUpstream();

        cancel(headers, 7, "user pressed stop");

        JsonNode answer = HttpServerTest.message(call.get(5, TimeUnit.SECONDS));
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        assertThat(seconds).as("not after the API's own delay").isLessThan(SLOW_MS / 1000);
        assertThat(answer.at("/result/isError").asBoolean()).isTrue();
        assertThat(answer.at("/result/content/0/text").asText()).contains("cancelled").contains("user pressed stop");

        // the audit says so, with the code
        List<String> lines = Files.readAllLines(dir.resolve("data/audit/audit.jsonl"));
        JsonNode event = lines.stream().map(l -> {
            try {
                return JSON.readTree(l);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).filter(e -> e.path("type").asText().equals("tool.call")).findFirst().orElseThrow();
        assertThat(event.path("outcome").asText()).isEqualTo("cancelled");
        assertThat(event.path("code").asText()).isEqualTo("CANCELLED");
    }

    JsonNode lastCallEvent() throws Exception {
        JsonNode found = null;
        for (String line : Files.readAllLines(dir.resolve("data/audit/audit.jsonl"))) {
            JsonNode event = JSON.readTree(line);
            if (event.path("type").asText().equals("tool.call")) {
                found = event;
            }
        }
        return found;
    }

    @Test
    void theAuditRecordsTheCancelAndTheTransport() throws Exception {
        slow();
        Map<String, String> headers = session();
        CompletableFuture<HttpResponse<String>> call = postAsync(CALL.formatted(7), headers);
        awaitUpstream();
        cancel(headers, 7, "too slow");
        call.get(5, TimeUnit.SECONDS);

        JsonNode mcp = lastCallEvent().path("mcp");
        assertThat(mcp.path("transport").asText()).isEqualTo("http");
        assertThat(mcp.path("revision").asText()).isEqualTo("2025-11-25");
        assertThat(mcp.at("/cancelled/reason").asText()).isEqualTo("too slow");
        assertThat(mcp.has("progress")).isFalse();
    }

    @Test
    void progressIsStreamedAndCountedInTheAudit() throws Exception {
        target.respond(new MockTarget.Response(200, Map.of("Content-Type", "application/json"),
                "{\"id\":\"c-1\"}".getBytes(StandardCharsets.UTF_8), 2500));
        Map<String, String> headers = session();
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"orders_customer_get","_meta":{"progressToken":"tok-1"},
                 "arguments":{"instance":"prod-eu","path":{"id":"c-1"}}}}""", headers);

        // the progress went down this request's own stream, then the result
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(response.body()).contains("notifications/progress").contains("tok-1").contains("\"id\":9");
        // increasing progress with the most the wait can come to (the instance's timeout), and a message
        List<JsonNode> progress = new ArrayList<>();
        for (String line : response.body().split("\n")) {
            if (line.startsWith("data:") && line.contains("notifications/progress")) {
                progress.add(JSON.readTree(line.substring(5).strip()));
            }
        }
        assertThat(progress).hasSizeGreaterThanOrEqualTo(2);
        double last = 0;
        for (JsonNode notification : progress) {
            double value = notification.at("/params/progress").asDouble();
            assertThat(value).isGreaterThan(last);
            last = value;
            assertThat(notification.at("/params/total").asDouble()).isGreaterThanOrEqualTo(value);
            assertThat(notification.at("/params/message").asText()).startsWith("Waiting for orders on prod-eu");
        }
        JsonNode mcp = lastCallEvent().path("mcp");
        assertThat(mcp.at("/progress/requested").asBoolean()).isTrue();
        assertThat(mcp.at("/progress/sent").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void anUpstreamFailureIsReportedToTheClientAsAMcpLogMessage() throws Exception {
        target.respond(MockTarget.Response.json(503, "{\"message\":\"down\"}"));
        Map<String, String> headers = session();
        HttpResponse<String> response = post(CALL.formatted(11), headers);
        assertThat(response.body()).contains("notifications/message").contains("\"logger\":\"mcpgp\"").contains("UPSTREAM_5XX");

        // a client that asked for errors only isn't sent warnings
        post("{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"logging/setLevel\",\"params\":{\"level\":\"error\"}}", headers);
        target.respond(MockTarget.Response.json(503, "{\"message\":\"down\"}"));
        assertThat(post(CALL.formatted(13), headers).body()).doesNotContain("notifications/message");
    }

    @Test
    void theClientCanSetTheLogLevel() throws Exception {
        Map<String, String> headers = session();
        JsonNode answer = HttpServerTest.message(post("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"logging/setLevel\",\"params\":{\"level\":\"warning\"}}", headers));
        assertThat(answer.has("error")).isFalse();
        assertThat(answer.has("result")).isTrue();
    }

    @Test
    void theSessionKeepsWorkingAfterACancel() throws Exception {
        slow();
        Map<String, String> headers = session();
        CompletableFuture<HttpResponse<String>> call = postAsync(CALL.formatted(7), headers);
        awaitUpstream();
        cancel(headers, 7, null);
        call.get(5, TimeUnit.SECONDS);

        target.respond(MockTarget.Response.json(200, "{\"id\":\"c-1\"}"));
        JsonNode next = HttpServerTest.message(post(CALL.formatted(8), headers));
        assertThat(next.at("/result/isError").asBoolean()).isFalse();
    }

    @Test
    void aCancelThatArrivesTooLateOrNamesNothingChangesNothing() throws Exception {
        target.respond(MockTarget.Response.json(200, "{\"id\":\"c-1\"}"));
        Map<String, String> headers = session();
        JsonNode done = HttpServerTest.message(post(CALL.formatted(7), headers));
        assertThat(done.at("/result/isError").asBoolean()).isFalse();

        cancel(headers, 7, "late");        // already answered
        cancel(headers, 999, null);        // never existed

        target.respond(MockTarget.Response.json(200, "{\"id\":\"c-1\"}"));
        assertThat(HttpServerTest.message(post(CALL.formatted(8), headers)).at("/result/isError").asBoolean()).isFalse();
    }

    @Test
    void anotherSessionCannotCancelTheCall() throws Exception {
        target.respond(new MockTarget.Response(200, Map.of("Content-Type", "application/json"),
                "{\"id\":\"c-1\"}".getBytes(StandardCharsets.UTF_8), 1500));
        Map<String, String> owner = session();
        Map<String, String> other = session();
        CompletableFuture<HttpResponse<String>> call = postAsync(CALL.formatted(7), owner);
        awaitUpstream();

        cancel(other, 7, "not mine");       // the same request id, another session

        JsonNode answer = HttpServerTest.message(call.get(10, TimeUnit.SECONDS));
        assertThat(answer.at("/result/isError").asBoolean()).isFalse();
    }
}
