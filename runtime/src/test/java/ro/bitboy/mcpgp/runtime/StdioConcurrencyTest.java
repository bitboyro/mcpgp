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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Several requests at once over stdio, as an agent sends parallel tool calls: they run side by side,
 * a quick request isn't held up behind slow ones, and every answer is one whole JSON line with its id.
 */
class StdioConcurrencyTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();
    static final int CALLS = 8;
    static final long SLOW_MS = 1500;

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
    void parallelCallsRunSideBySideAndAQuickRequestIsNotHeldUp(@TempDir Path dir) throws Exception {
        target = new MockTarget();
        for (int i = 0; i < CALLS; i++) {
            target.respond(new MockTarget.Response(200, Map.of("Content-Type", "application/json"),
                    ("{\"id\":\"c-" + i + "\",\"name\":\"Customer " + i + "\"}").getBytes(StandardCharsets.UTF_8), SLOW_MS));
        }
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

        // the agent fires its calls at once, then something quick
        long start = System.nanoTime();
        for (int i = 0; i < CALLS; i++) {
            send(Map.of("jsonrpc", "2.0", "id", 100 + i, "method", "tools/call", "params", Map.of("name", "orders_customer_get",
                    "arguments", Map.of("instance", "prod-eu", "path", Map.of("id", "c-" + i)))));
        }
        send(Map.of("jsonrpc", "2.0", "id", 999, "method", "tools/list", "params", Map.of()));

        Map<Integer, Long> answeredAfterMs = new LinkedHashMap<>();
        Map<Integer, JsonNode> answers = new LinkedHashMap<>();
        while (answers.size() < CALLS + 1) {
            JsonNode message = read();      // a line that isn't whole JSON fails here
            if (message.has("id")) {
                answers.put(message.get("id").asInt(), message);
                answeredAfterMs.put(message.get("id").asInt(), (System.nanoTime() - start) / 1_000_000);
            }
        }
        long totalMs = (System.nanoTime() - start) / 1_000_000;

        for (int i = 0; i < CALLS; i++) {
            JsonNode result = answers.get(100 + i).path("result");
            assertThat(result.path("isError").asBoolean()).as(answers.get(100 + i).toString()).isFalse();
        }
        assertThat(answers.get(999).at("/result/tools").size()).isPositive();
        assertThat(target.received).hasSize(CALLS);
        assertThat(answeredAfterMs.get(999)).as("tools/list waits for no tool call").isLessThan(SLOW_MS);
        assertThat(totalMs).as("the calls ran side by side, not one after another").isLessThan(SLOW_MS * 3);
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
