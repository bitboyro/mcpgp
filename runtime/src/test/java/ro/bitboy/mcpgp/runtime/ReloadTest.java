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
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Config reload on a running server (runtime-config §4.6). */
class ReloadTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;
    ConfigurableApplicationContext context;
    MockTarget first;
    MockTarget second;
    OutputStream toServer;
    final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
    int nextId = 1;
    Path config;

    @AfterEach
    void stop() throws IOException {
        if (toServer != null) {
            toServer.close();
        }
        if (context != null) {
            context.close();
        }
        first.close();
        second.close();
    }

    void start(String instances) throws Exception {
        first = new MockTarget();
        second = new MockTarget();
        config = dir.resolve("orders-mcp.yaml");
        Files.writeString(config, instances);
        Startup startup = Startup.load(new String[] {"--manifest", REPO.resolve("examples/manifest/orders-mcp.manifest.json").toString(),
                "--config", config.toString(), "--storage.root", dir.resolve("data").toString()}, Map.of(), dir);
        PipedInputStream serverIn = new PipedInputStream(1 << 16);
        toServer = new PipedOutputStream(serverIn);
        PipedOutputStream serverOut = new PipedOutputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new PipedInputStream(serverOut, 1 << 20), StandardCharsets.UTF_8));
        Thread pump = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    messages.add(JSON.readTree(line));
                }
            } catch (IOException e) {
                // closed
            }
        });
        pump.setDaemon(true);
        pump.start();
        context = McpgpServer.start(startup, new McpgpServer.Stdio(serverIn, serverOut, () -> { }));
        request("initialize", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(), "clientInfo", Map.of("name", "t", "version", "1")));
        send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
    }

    void send(Object message) throws IOException {
        toServer.write((JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
        toServer.flush();
    }

    JsonNode request(String method, Object params) throws Exception {
        int id = nextId++;
        send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        return await(m -> m.path("id").asInt(-1) == id, 10);
    }

    JsonNode await(java.util.function.Predicate<JsonNode> match, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        List<JsonNode> skipped = new ArrayList<>();
        try {
            while (System.nanoTime() < deadline) {
                JsonNode message = messages.poll(100, TimeUnit.MILLISECONDS);
                if (message != null && match.test(message)) {
                    return message;
                }
                if (message != null) {
                    skipped.add(message);
                }
            }
            throw new AssertionError("no matching message within " + seconds + " s");
        } finally {
            messages.addAll(skipped);
        }
    }

    List<String> instanceEnum() throws Exception {
        JsonNode tools = request("tools/list", Map.of()).at("/result/tools");
        for (JsonNode tool : tools) {
            if (tool.path("name").asText().equals("orders_customer_get")) {
                return JSON.convertValue(tool.at("/inputSchema/properties/instance/enum"), List.class);
            }
        }
        throw new AssertionError("orders_customer_get not listed");
    }

    Reloader reloader() {
        return context.getBean(Reloader.class);
    }

    @Test
    void instancesChangeWhileRunning() throws Exception {
        start("instances:\n  - { id: a, baseUrl: a.example }\n");
        Files.writeString(config, "instances:\n  - { id: a, baseUrl: \"" + first.address() + "\" }\n");
        reloader().reload("test");
        assertThat(instanceEnum()).containsExactly("a");

        Files.writeString(config, "instances:\n  - { id: a, baseUrl: \"" + second.address() + "\" }\n  - { id: b, baseUrl: \""
                + first.address() + "\" }\n");
        assertThat(reloader().reload("test")).isTrue();
        await(m -> m.path("method").asText().equals("notifications/tools/list_changed"), 5);
        assertThat(instanceEnum()).containsExactly("a", "b");

        // calls now use the new address of a
        request("tools/call", Map.of("name", "orders_customer_get", "arguments", Map.of("instance", "a", "path", Map.of("id", "1"))));
        assertThat(second.received).hasSize(1);
        assertThat(first.received).isEmpty();
    }

    @Test
    void aConfigThatDoesNotLoadIsRejected() throws Exception {
        start("instances:\n  - { id: a, baseUrl: a.example }\n");
        Files.writeString(config, "instances:\n  - { id: a, baseUrl: \"ftp://nope\" }\n");
        assertThat(reloader().reload("test")).isFalse();
        assertThat(instanceEnum()).containsExactly("a");
        String audit = Files.readString(dir.resolve("data/audit/audit.jsonl"));
        assertThat(audit).contains("\"type\":\"config.reload\"").contains("\"code\":\"CONFIG_INVALID\"");
    }

    @Test
    void reloadsWhenTheFileChangesOrOnRequest() throws Exception {
        start("instances:\n  - { id: a, baseUrl: a.example }\n");
        Files.writeString(config, "instances:\n  - { id: a, baseUrl: a.example }\n  - { id: c, baseUrl: c.example }\n");
        awaitEnum(List.of("a", "c"));

        Files.writeString(config, "instances:\n  - { id: d, baseUrl: d.example }\n");
        // the reload command, as `<server> reload` leaves it
        new ServerCommands(Map.of(), dir, System.in, new java.io.PrintStream(OutputStream.nullOutputStream()))
                .run(new String[] {"reload", "--manifest", REPO.resolve("examples/manifest/orders-mcp.manifest.json").toString(),
                        "--config", config.toString(), "--storage.root", dir.resolve("data").toString()});
        awaitEnum(List.of("d"));
    }

    /** Waits until tools/list shows these instances (file watching may take a few seconds on macOS). */
    void awaitEnum(List<String> expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        List<String> seen = List.of();
        while (System.nanoTime() < deadline) {
            seen = instanceEnum();
            if (seen.equals(expected)) {
                return;
            }
            Thread.sleep(200);
        }
        assertThat(seen).containsExactlyElementsOf(expected);
    }
}
