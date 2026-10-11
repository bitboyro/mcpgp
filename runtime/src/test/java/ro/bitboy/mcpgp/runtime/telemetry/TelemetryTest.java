package ro.bitboy.mcpgp.runtime.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.call.MockTarget;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.mcp.McpServerFactory;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Telemetry (audit-and-errors §4): off by default; OTLP push and Prometheus scrape; never arguments. */
class TelemetryTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Manifest ORDERS = Manifest.file(REPO.resolve("examples/manifest/orders-mcp.manifest.json"));
    static final ObjectMapper JSON = new ObjectMapper();
    static final String SECRET_ARGUMENT = "customer-7f3a-private";

    @TempDir
    Path dir;
    MockTarget target;

    @AfterEach
    void stop() {
        Telemetry.current().close();
        if (target != null) {
            target.close();
        }
    }

    McpServerFactory server() throws Exception {
        target = new MockTarget();
        Path file = dir.resolve("orders-mcp.yaml");
        Files.writeString(file, "instances:\n  - { id: prod-eu, baseUrl: \"" + target.address() + "\" }\n");
        RuntimeConfig config = new ConfigLoader(new Schemas()).load(ORDERS, new ConfigLoader.Options(Optional.of(file), dir, Map.of(),
                Map.of("storage.root", dir.resolve("data").toString())));
        return new McpServerFactory(ORDERS, config);
    }

    static void callCustomer(McpServerFactory server) {
        McpSchema.CallToolResult result = server.call(null, new McpSchema.CallToolRequest("orders_customer_get",
                Map.of("instance", "prod-eu", "path", Map.of("id", SECRET_ARGUMENT)), null));
        assertThat(result.isError()).as("%s", result.content()).isFalse();
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void isOffByDefaultAndWhenTheEnvironmentSaysSo() throws Exception {
        assertThat(Telemetry.start(JSON.readTree("{}"), "orders-mcp", "2.0.0", Map.of())).isSameAs(Telemetry.current());
        Telemetry.Call call = Telemetry.current().call("x", "orders");
        call.close();     // nothing to record, nothing fails
        Telemetry off = Telemetry.start(JSON.readTree("{\"enabled\":true}"), "orders-mcp", "2.0.0", Map.of("OTEL_SDK_DISABLED", "true"));
        assertThat(off).isSameAs(Telemetry.start(JSON.readTree("{}"), "orders-mcp", "2.0.0", Map.of()));
    }

    @Test
    void prometheusShowsCallsDurationsAndInstancesButNoArguments() throws Exception {
        Telemetry.start(JSON.readTree("{\"enabled\":true,\"exporter\":\"prometheus\"}"), "orders-mcp", "2.0.0", Map.of());
        McpServerFactory server = server();
        target.respond(MockTarget.Response.json(200, "{\"id\":\"c-1\"}"));
        callCustomer(server);
        server.call(null, new McpSchema.CallToolRequest("orders_customer_get", Map.of("instance", "nowhere", "path", Map.of("id", "x")), null));

        String metrics = new String(Telemetry.current().scrape().orElseThrow(), StandardCharsets.UTF_8);
        assertThat(metrics).contains("mcpgp_tool_calls_total{").contains("mcpgp_tool=\"orders_customer_get\"").contains("mcpgp_outcome=\"ok\"")
                .contains("mcpgp_outcome=\"error\"").contains("mcpgp_tool_duration_seconds_bucket").contains("mcpgp_instance_up{")
                .contains("mcpgp_instance=\"prod-eu\"").contains("service_name=\"orders-mcp\"");
        assertThat(metrics).doesNotContain(SECRET_ARGUMENT);
        assertThat(Telemetry.scrapeContentType()).startsWith("text/plain");
    }

    @Test
    void metricsAreServedBesideHealthNotBesideTheMcpEndpoint() throws Exception {
        target = new MockTarget();
        int port = freePort();
        int management = freePort();
        Path file = dir.resolve("orders-mcp.yaml");
        Files.writeString(file, """
                server:
                  transport: http
                  port: %d
                  management: { port: %d }
                  auth: { mode: static, keys: [ { name: team-a, value: "${env:KEY}" } ] }
                telemetry: { enabled: true, exporter: prometheus }
                instances:
                  - { id: prod-eu, baseUrl: "%s" }
                """.formatted(port, management, target.address()));
        var startup = ro.bitboy.mcpgp.runtime.Startup.load(new String[] {"--manifest", REPO.resolve("examples/manifest/orders-mcp.manifest.json").toString(),
                "--config", file.toString(), "--storage.root", dir.resolve("data").toString()}, Map.of("KEY", "k-team-a-0123456789"), dir);
        try (var context = ro.bitboy.mcpgp.runtime.McpgpServer.start(startup, new ro.bitboy.mcpgp.runtime.McpgpServer.Stdio(System.in, System.out, () -> { }))) {
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> onManagement = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + management + "/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(onManagement.statusCode()).isEqualTo(200);
            assertThat(onManagement.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
            assertThat(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        }
        // a server without exporter: prometheus has no metrics to show
        assertThat(Telemetry.current().scrape()).isEmpty();
    }

    @Test
    void otlpSendsASpanPerCallWithItsUpstreamRequest() throws Exception {
        Map<String, ByteArrayOutputStream> received = new ConcurrentHashMap<>();
        HttpServer collector = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        collector.createContext("/", x -> {
            received.computeIfAbsent(x.getRequestURI().getPath(), k -> new ByteArrayOutputStream()).write(x.getRequestBody().readAllBytes());
            x.sendResponseHeaders(200, -1);
            x.close();
        });
        collector.start();
        try {
            JsonNode config = JSON.readTree("{\"enabled\":true,\"otlp\":{\"protocol\":\"http/protobuf\",\"endpoint\":\"http://127.0.0.1:"
                    + collector.getAddress().getPort() + "\",\"headers\":{\"x-api-key\":\"collector-key\"}}}");
            Telemetry.start(config, "orders-mcp", "2.0.0", Map.of());
            McpServerFactory server = server();
            target.respond(MockTarget.Response.json(200, "{\"id\":\"c-1\"}"));
            callCustomer(server);
            Telemetry.current().close();      // flushes spans and metrics

            String traces = received.getOrDefault("/v1/traces", new ByteArrayOutputStream()).toString(StandardCharsets.ISO_8859_1);
            assertThat(traces).contains("tool orders_customer_get").contains("mcpgp.instance").contains("prod-eu").contains("GET")
                    .contains("http.response.status_code").contains("orders-mcp");
            assertThat(received).containsKey("/v1/metrics");
            assertThat(received.get("/v1/metrics").toString(StandardCharsets.ISO_8859_1)).contains("mcpgp.tool.calls");
            for (ByteArrayOutputStream body : received.values()) {
                assertThat(body.toString(StandardCharsets.ISO_8859_1)).doesNotContain(SECRET_ARGUMENT);
            }
        } finally {
            collector.stop(0);
        }
    }
}
