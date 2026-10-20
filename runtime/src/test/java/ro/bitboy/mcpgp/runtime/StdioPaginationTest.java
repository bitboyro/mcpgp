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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code server.pageSize} over stdio: the same pages and cursors as over HTTP. */
class StdioPaginationTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();

    ConfigurableApplicationContext context;
    OutputStream toServer;
    BufferedReader fromServer;
    int ids = 10;

    @AfterEach
    void stop() throws IOException {
        if (toServer != null) {
            toServer.close();
        }
        if (context != null) {
            context.close();
        }
    }

    void start(Path dir, String server) throws Exception {
        Path file = dir.resolve("server.yaml");
        Files.writeString(file, server + "instances:\n  - { id: prod-eu, baseUrl: \"http://127.0.0.1:1\", contract: \"2.0\" }\n");
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
    }

    JsonNode list(String cursor) throws Exception {
        int id = ids++;
        send(Map.of("jsonrpc", "2.0", "id", id, "method", "tools/list", "params", cursor == null ? Map.of() : Map.of("cursor", cursor)));
        JsonNode answer = read();
        assertThat(answer.path("id").asInt()).isEqualTo(id);
        return answer;
    }

    static List<String> names(JsonNode answer) {
        List<String> names = new ArrayList<>();
        answer.at("/result/tools").forEach(tool -> names.add(tool.path("name").asText()));
        return names;
    }

    @Test
    void pagesAddUpToTheWholeList(@TempDir Path dir) throws Exception {
        start(dir, "server:\n  pageSize: 2\n");
        List<String> paged = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = list(cursor);
            assertThat(page.has("error")).as(page.toString()).isFalse();
            assertThat(names(page)).hasSizeLessThanOrEqualTo(2);
            paged.addAll(names(page));
            cursor = page.at("/result").has("nextCursor") ? page.at("/result/nextCursor").asText() : null;
            pages++;
        } while (cursor != null && pages < 100);
        assertThat(pages).isGreaterThan(1);
        assertThat(paged).doesNotHaveDuplicates().contains("instances_list");

        assertThat(list("garbage").at("/error/code").asInt()).isEqualTo(-32602);
        // a tool call is not a list: it is answered whole
        send(Map.of("jsonrpc", "2.0", "id", 99, "method", "tools/call", "params", Map.of("name", "instances_list", "arguments", Map.of())));
        assertThat(read().at("/result/isError").asBoolean()).isFalse();
    }

    @Test
    void offByDefault(@TempDir Path dir) throws Exception {
        start(dir, "");
        JsonNode all = list(null);
        assertThat(all.at("/result").has("nextCursor")).isFalse();
        assertThat(names(all)).contains("instances_list");
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
