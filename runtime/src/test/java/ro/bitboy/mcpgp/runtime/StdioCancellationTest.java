package ro.bitboy.mcpgp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import ro.bitboy.mcpgp.runtime.call.MockTarget;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code notifications/cancelled} over stdio: the call waiting for a slow API stops; the others go on. */
class StdioCancellationTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();
    static final long SLOW_MS = 8000;

    ConfigurableApplicationContext context;
    OutputStream toServer;
    BufferedReader fromServer;
    MockTarget target;

    @AfterEach
    void stop() throws IOException {
        if (toServer != null) {
            toServer.close();
        }
        if (context != null) {
            context.close();
        }
        if (target != null) {
            target.close();
        }
    }

    @Test
    void aCancelledCallStopsAndTheOtherCallsFinish(@TempDir Path dir) throws Exception {
        target = new MockTarget();
        // the first request to arrive is the one that gets cancelled; the second answers normally
        target.respond(new MockTarget.Response(200, Map.of("Content-Type", "application/json"),
                "{\"id\":\"c-1\"}".getBytes(StandardCharsets.UTF_8), SLOW_MS));
        target.respond(MockTarget.Response.json(200, "{\"id\":\"c-2\"}"));
        Path file = dir.resolve("server.yaml");
        Files.writeString(file, "instances:\n  - { id: prod-eu, baseUrl: \"" + target.address() + "\", contract: \"2.0\" }\n");
        Startup startup = Startup.load(new String[] {"--manifest", REPO.resolve("examples/manifest/orders-mcp.manifest.json").toString(),
                "--config", file.toString(), "--storage.root", dir.resolve("data").toString()}, Map.of(), dir);
        java.nio.channels.Pipe in = java.nio.channels.Pipe.open();
        java.nio.channels.Pipe out = java.nio.channels.Pipe.open();
        toServer = java.nio.channels.Channels.newOutputStream(in.sink());
        fromServer = new BufferedReader(new InputStreamReader(java.nio.channels.Channels.newInputStream(out.source()), StandardCharsets.UTF_8));
        context = McpgpServer.start(startup, new McpgpServer.Stdio(java.nio.channels.Channels.newInputStream(in.source()),
                java.nio.channels.Channels.newOutputStream(out.sink()), () -> { }));

        send(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of("protocolVersion", "2025-11-25",
                "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1"))));
        assertThat(read().path("id").asInt()).isEqualTo(1);
        send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));

        long start = System.nanoTime();
        send(call(100));
        awaitUpstream(1);
        send(call(101));                         // answered at once by the second queued response
        JsonNode first = read();
        assertThat(first.path("id").asInt()).isEqualTo(101);
        assertThat(first.at("/result/isError").asBoolean()).isFalse();

        send(Map.of("jsonrpc", "2.0", "method", "notifications/cancelled", "params", Map.of("requestId", 100, "reason", "stop")));
        // the spec: a cancelled request is not answered; the slow API request was stopped, so we are well before its delay
        target.respond(MockTarget.Response.json(200, "{\"id\":\"c-3\"}"));
        send(call(102));
        JsonNode next = read();
        assertThat(next.path("id").asInt()).as("the first answer after the cancel is the next call's, not 100's").isEqualTo(102);
        assertThat(next.at("/result/isError").asBoolean()).isFalse();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(SLOW_MS);

        // the audit still says what happened
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String audit = "";
        while (!audit.contains("\"outcome\":\"cancelled\"") && System.nanoTime() < deadline) {
            Thread.sleep(50);
            audit = Files.readString(dir.resolve("data/audit/audit.jsonl"));
        }
        assertThat(audit).contains("\"outcome\":\"cancelled\"").contains("\"reason\":\"stop\"");
    }

    static Map<String, Object> call(int id) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call", "params", Map.of("name", "orders_customer_get",
                "arguments", Map.of("instance", "prod-eu", "path", Map.of("id", "c-1"))));
    }

    void awaitUpstream(int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (target.received.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(target.received).hasSizeGreaterThanOrEqualTo(count);
    }

    void send(Object message) throws IOException {
        toServer.write((JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
        toServer.flush();
    }

    JsonNode read() throws Exception {
        String line = CompletableFuture.supplyAsync(() -> {
            try {
                return fromServer.readLine();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).get(30, TimeUnit.SECONDS);
        return JSON.readTree(line);
    }
}
