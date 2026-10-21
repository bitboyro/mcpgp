package ro.bitboy.mcpgp.runtime.http;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import ro.bitboy.mcpgp.runtime.McpgpServer;
import ro.bitboy.mcpgp.runtime.Startup;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code server.pageSize} over Streamable HTTP: lists in pages with opaque cursors; off by default. */
class PaginationTest {

    @TempDir
    Path dir;
    ConfigurableApplicationContext context;
    int port;

    @AfterEach
    void stop() {
        if (context != null) {
            context.close();
        }
    }

    Map<String, String> start(String pageSize) throws Exception {
        port = HttpServerTest.freePort();
        Path config = dir.resolve("server.yaml");
        Files.writeString(config, """
                server: { transport: http, port: %d%s }
                instances:
                  - { id: prod-eu, baseUrl: "http://127.0.0.1:1", contract: "2.0" }
                """.formatted(port, pageSize));
        Startup startup = Startup.load(new String[] {"--manifest", HttpServerTest.REPO.resolve("examples/manifest/orders-mcp.manifest.json").toString(),
                "--config", config.toString(), "--storage.root", dir.resolve("data").toString()}, Map.of(), dir);
        context = McpgpServer.start(startup, new McpgpServer.Stdio(System.in, System.out, () -> { }));
        HttpResponse<String> init = post(HttpServerTest.INITIALIZE, Map.of());
        String id = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        Map<String, String> headers = Map.of("Mcp-Session-Id", id, "Mcp-Protocol-Version", "2025-11-25");
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", headers);
        return headers;
    }

    HttpResponse<String> post(String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return HttpServerTest.HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode list(Map<String, String> headers, String cursor) throws Exception {
        return HttpServerTest.message(post("{\"jsonrpc\":\"2.0\",\"id\":" + (int) (Math.random() * 100000 + 10) + ",\"method\":\"tools/list\",\"params\":"
                + (cursor == null ? "{}" : "{\"cursor\":\"" + cursor + "\"}") + "}", headers));
    }

    static List<String> names(JsonNode answer) {
        List<String> names = new ArrayList<>();
        answer.at("/result/tools").forEach(tool -> names.add(tool.path("name").asText()));
        return names;
    }

    @Test
    void toolsComeInPagesThatAddUpToTheWholeList() throws Exception {
        Map<String, String> headers = start("");
        List<String> everything = new ArrayList<>(names(list(headers, null)));
        assertThat(list(headers, null).at("/result").has("nextCursor")).isFalse();
        context.close();

        headers = start(", pageSize: 3");
        List<String> paged = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = list(headers, cursor);
            assertThat(page.has("error")).as(page.toString()).isFalse();
            List<String> names = names(page);
            assertThat(names).hasSizeLessThanOrEqualTo(3);
            paged.addAll(names);
            cursor = page.at("/result").has("nextCursor") ? page.at("/result/nextCursor").asText() : null;
            pages++;
        } while (cursor != null && pages < 100);

        assertThat(pages).isGreaterThan(1);
        assertThat(paged).containsExactlyElementsOf(everything);
    }

    @Test
    void aCursorThatIsNotOurs() throws Exception {
        Map<String, String> headers = start(", pageSize: 3");
        JsonNode answer = list(headers, "not-a-cursor");
        assertThat(answer.at("/error/code").asInt()).isEqualTo(-32602);
        // made for a list of another size: refused, not misread
        String foreign = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("2:000000000000".getBytes());
        assertThat(list(headers, foreign).at("/error/code").asInt()).isEqualTo(-32602);
    }

    @Test
    void otherRequestsAreUntouched() throws Exception {
        Map<String, String> headers = start(", pageSize: 1");
        target: {
            JsonNode call = HttpServerTest.message(post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"instances_list\",\"arguments\":{}}}", headers));
            assertThat(call.at("/result/isError").asBoolean()).isFalse();
        }
    }
}
