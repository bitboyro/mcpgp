package ro.bitboy.mcpgp.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** `mcpgp push` against a stand-in platform: upload, build, the local comparison, release. */
class PushTest {

    static final Path ORDERS = Path.of("..", "examples", "platform", "orders").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;
    Path definition;
    HttpServer platform;
    final List<String> requests = new ArrayList<>();
    final TreeSet<String> uploaded = new TreeSet<>();
    boolean exists;
    boolean agent;
    JsonNode builtManifest;
    int polls;

    final StringWriter out = new StringWriter();
    final StringWriter err = new StringWriter();

    @BeforeEach
    void setUp() throws IOException {
        // a project like `mcpgp init` writes, with a secret lying next to it
        Files.createDirectories(dir.resolve("contracts"));
        Files.copy(ORDERS.resolve("contracts/orders-2.0.yaml"), dir.resolve("contracts/orders-2.0.yaml"));
        definition = dir.resolve("orders.mcpgp.yaml");
        Files.writeString(definition, Scaffold.definition("orders-mcp", "Orders", "orders", "2.0", "contracts/orders-2.0.yaml"));
        Files.writeString(dir.resolve("mcpgp.key"), "secret");
        Files.createDirectories(dir.resolve("tests"));
        Files.writeString(dir.resolve("tests/smoke.yaml"), "cases: []\n");
        builtManifest = new Compiler(new Schemas()).compile(definition, Compiler.Options.defaults()).manifest().orElseThrow();

        platform = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        platform.createContext("/", this::handle);
        platform.start();
        Main.env = Map.of("MCPGP_TOKEN", "t0ken", "MCPGP_PLATFORM", "http://127.0.0.1:" + platform.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        platform.stop(0);
        Main.env = System.getenv();
    }

    void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String line = exchange.getRequestMethod() + " " + path + (exchange.getRequestURI().getQuery() == null ? "" : "?" + exchange.getRequestURI().getQuery());
            requests.add(line);
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (!"Bearer t0ken".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 401, "{\"title\":\"Unauthorized\"}");
                return;
            }
            if (line.startsWith("POST /api/v1/servers") && !path.endsWith("/builds")) {
                try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body))) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        uploaded.add(entry.getName());
                    }
                }
            }
            switch (exchange.getRequestMethod() + " " + path) {
                case "GET /api/v1/servers/orders-mcp" -> send(exchange, exists ? 200 : 404, exists ? "{\"id\":\"orders-mcp\",\"latestVersion\":4}" : "{}");
                case "POST /api/v1/servers" -> send(exchange, 201, "{\"id\":\"orders-mcp\",\"latestVersion\":1}");
                case "POST /api/v1/servers/orders-mcp/versions" -> send(exchange, 201, "{\"id\":\"orders-mcp\",\"latestVersion\":5}");
                case "POST /api/v1/servers/orders-mcp/builds" -> send(exchange, 202, "{\"id\":\"b-1\",\"status\":\"queued\"}");
                case "GET /api/v1/builds/b-1" -> send(exchange, 200, polls++ == 0 ? "{\"id\":\"b-1\",\"status\":\"running\"}"
                        : "{\"id\":\"b-1\",\"status\":\"succeeded\",\"serverVersion\":\"2.0.0+2\",\"artifacts\":[{\"name\":\"orders-mcp-2.0.0+2.zip\"},"
                        + "{\"name\":\"orders-mcp-2.0.0+2.manifest.json\"}]}");
                case "GET /api/v1/builds/b-1/artifacts/orders-mcp-2.0.0+2.manifest.json" -> send(exchange, 200, JSON.writeValueAsString(builtManifest));
                case "POST /api/v1/builds/b-1/release" -> send(exchange, agent ? 202 : 200,
                        agent ? "{\"approval\":{\"id\":\"ap-9\"}}" : "{\"id\":\"b-1\",\"released\":true}");
                default -> send(exchange, 404, "{\"title\":\"Not found\"}");
            }
        }
    }

    static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    int run(String... args) {
        return Main.run(new PrintWriter(out, true), new PrintWriter(err, true), args);
    }

    @Test
    void aNewServerIsCreatedWithOnlyTheFilesTheDefinitionUses() {
        assertThat(run("push", "--message", "from CI", definition.toString())).as(err.toString()).isZero();
        assertThat(requests).containsExactly("GET /api/v1/servers/orders-mcp", "POST /api/v1/servers?id=orders-mcp&title=Orders&message=from CI");
        assertThat(uploaded).containsExactly("contracts/orders-2.0.yaml", "orders.mcpgp.yaml");       // no key, no tests
        assertThat(out.toString()).contains("created orders-mcp with version 1 (2 files)");
    }

    @Test
    void anExistingServerGetsAVersionBuiltComparedAndReleased() {
        exists = true;
        assertThat(run("push", "--build", "--verify-local", "--release", definition.toString())).as(err.toString()).isZero();
        assertThat(requests).contains("POST /api/v1/servers/orders-mcp/versions?message=pushed with mcpgp " + Compiler.VERSION,
                "POST /api/v1/servers/orders-mcp/builds", "POST /api/v1/builds/b-1/release");
        assertThat(out.toString()).contains("now at version 5").contains("✓ built 2.0.0+2 (b-1)").contains("✓ same as the local compile")
                .contains("✓ released 2.0.0+2");
    }

    @Test
    void aDifferentPlatformBuildFailsTheComparison() {
        exists = true;
        ((ObjectNode) builtManifest.path("server")).put("description", "changed by another compiler");
        ((ObjectNode) builtManifest.path("build")).put("cli", "0.0.9");
        assertThat(run("push", "--build", "--verify-local", definition.toString())).isEqualTo(1);
        assertThat(err.toString()).contains("differs from the local compile").contains("compiler 0.0.9 on the platform");
    }

    @Test
    void anAgentsReleaseWaitsForApproval() throws IOException {
        exists = true;
        agent = true;
        assertThat(run("push", "--release", "--json", definition.toString())).isZero();
        JsonNode summary = JSON.readTree(out.toString());
        assertThat(summary.path("approval").asText()).isEqualTo("ap-9");
        assertThat(summary.path("released").isMissingNode()).isTrue();
    }

    @Test
    void tokensAndAddressesAreChecked() {
        Main.env = Map.of("MCPGP_PLATFORM", "http://127.0.0.1:1");
        assertThat(run("push", definition.toString())).isEqualTo(2);
        assertThat(err.toString()).contains("set MCPGP_TOKEN");
        Main.env = Map.of("MCPGP_TOKEN", "t");
        assertThat(run("push", "--platform", "http://mcpgp.example.com", definition.toString())).isEqualTo(2);
        assertThat(err.toString()).contains("must be https");
        Main.env = Map.of("MCPGP_TOKEN", "wrong", "MCPGP_PLATFORM", "http://127.0.0.1:" + platform.getAddress().getPort());
        assertThat(run("push", definition.toString())).isEqualTo(1);
        assertThat(err.toString()).contains("HTTP 401").contains("MCPGP_TOKEN");
    }
}
