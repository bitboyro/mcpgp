package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.mcp.McpServerFactory;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a call does when the target closes the connection without answering (tools-contract §6):
 * GET and HEAD are sent a second time, once (the JDK client's own retry, which HTTP allows for them);
 * every other method is sent once. Either way the agent gets UPSTREAM_UNREACHABLE when nothing answers.
 */
class UpstreamRetriesTest {

    static final Path ORDERS = Path.of("..").toAbsolutePath().normalize().resolve("examples/manifest/orders-mcp.manifest.json");

    @TempDir
    Path dir;
    ServerSocket target;
    final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        target = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.startVirtualThread(() -> {
            while (!target.isClosed()) {
                try (Socket socket = target.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                    String line = in.readLine();
                    if (line != null) {
                        requests.add(line.substring(0, line.lastIndexOf(' ')));
                    }
                    // closed without an answer
                } catch (IOException e) {
                    // the test ends
                }
            }
        });
    }

    @AfterEach
    void stop() throws IOException {
        target.close();
    }

    McpServerFactory server() throws IOException {
        Path file = dir.resolve("orders-mcp.yaml");
        Files.writeString(file, "instances:\n  - { id: prod, baseUrl: \"http://127.0.0.1:" + target.getLocalPort() + "\", contract: \"2.0\" }\n");
        Manifest manifest = Manifest.file(ORDERS);
        RuntimeConfig config = new ConfigLoader(new Schemas()).load(manifest, new ConfigLoader.Options(Optional.of(file), dir,
                Map.of(), Map.of("storage.root", dir.resolve("data").toString())));
        return new McpServerFactory(manifest, config);
    }

    static String code(McpSchema.CallToolResult result) {
        return new ObjectMapper().valueToTree(result.structuredContent()).path("code").asText();
    }

    @Test
    void aGetIsSentAtMostTwiceWhenTheTargetDropsTheConnection() throws IOException {
        McpSchema.CallToolResult result = server().call(null, new McpSchema.CallToolRequest("orders_customer_get",
                Map.of("instance", "prod", "path", Map.of("id", "c-1")), null));

        assertThat(code(result)).isEqualTo("UPSTREAM_UNREACHABLE");
        assertThat(requests).containsExactly("GET /api/v2/customers/c-1", "GET /api/v2/customers/c-1");
    }

    @Test
    void otherMethodsAreSentOnce() throws IOException {
        McpServerFactory server = server();
        McpSchema.CallToolResult created = server.call(null, new McpSchema.CallToolRequest("orders_customer_create",
                Map.of("instance", "prod", "body", Map.of("name", "Ada", "accountId", "a-1")), null));
        McpSchema.CallToolResult updated = server.call(null, new McpSchema.CallToolRequest("orders_customer_update",
                Map.of("instance", "prod", "path", Map.of("id", "c-1"), "body", Map.of("name", "Ada", "accountId", "a-1")), null));

        assertThat(code(created)).isEqualTo("UPSTREAM_UNREACHABLE");
        assertThat(code(updated)).isEqualTo("UPSTREAM_UNREACHABLE");
        assertThat(requests).containsExactly("POST /api/v2/customers", "PUT /api/v2/customers/c-1");
    }

    @Test
    void theRetryAfterARefusedConnectionIsOff() {
        HttpRetries.noConnectRetry();
        assertThat(System.getProperty("jdk.httpclient.disableRetryConnect")).isEqualTo("true");
    }
}
