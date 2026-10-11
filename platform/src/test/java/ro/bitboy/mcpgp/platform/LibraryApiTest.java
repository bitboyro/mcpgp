package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The library and variants (spec/platform-api.md §2.1, §2.3) with the real platform examples: the
 * orders definition references mcpgp://acme/naming/acme-standard@2, the read-only variant extends
 * mcpgp://acme/servers/orders-mcp@latest.
 */
class LibraryApiTest {

    static final Path EXAMPLES = Path.of("..", "examples", "platform").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    static Path dir;
    static ConfigurableApplicationContext context;
    static String base;
    static String admin;

    @BeforeAll
    static void start() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        PlatformConfig config = TestStores.of(new PlatformConfig(dir.resolve("store"), "127.0.0.1", port, null, PlatformConfig.Retention.DEFAULT, "acme"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PlatformServer.init(config, new String[] {"init", "--admin", "ana"}, new PrintStream(out, true, StandardCharsets.UTF_8));
        admin = out.toString(StandardCharsets.UTF_8).lines().filter(l -> l.strip().startsWith("mcpgp_")).findFirst().orElseThrow().strip();
        context = PlatformServer.start(config);
        base = "http://127.0.0.1:" + port + "/api/v1";

        // the shared naming policy, at version 2 (as the orders definition references it)
        byte[] naming = Files.readAllBytes(EXAMPLES.resolve("workspace/naming/acme-standard.yaml"));
        assertThat(call("POST", "/library/naming?name=acme-standard", files(Map.of("acme-standard.yaml", "source: operationId\n"))).statusCode())
                .isEqualTo(201);
        assertThat(call("POST", "/library/naming/acme-standard/versions", files(Map.of("acme-standard.yaml",
                new String(naming, StandardCharsets.UTF_8)))).statusCode()).isEqualTo(201);
        assertThat(call("POST", "/servers?id=orders-mcp", project(EXAMPLES.resolve("orders"), Map.of())).statusCode()).isEqualTo(201);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    static HttpResponse<byte[]> call(String method, String path, byte[] body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + admin)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    static JsonNode json(HttpResponse<byte[]> response) throws IOException {
        return JSON.readTree(response.body());
    }

    static byte[] files(Map<String, String> files) throws IOException {
        return JSON.writeValueAsBytes(Map.of("files", files));
    }

    static byte[] project(Path folder, Map<String, String> changes) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.put(folder.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
        files.putAll(changes);
        return files(files);
    }

    static JsonNode manifest(String server) throws Exception {
        HttpResponse<byte[]> response = call("GET", "/servers/" + server + "/versions/latest/manifest", null);
        assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(200);
        return json(response);
    }

    @Test
    void definitionsUseLibraryItems() throws Exception {
        List<String> tools = manifest("orders-mcp").path("tools").findValuesAsText("name");
        // the same names as the CLI with --workspace (OrdersToolsTest)
        assertThat(tools).contains("orders_customer_get", "orders_customer_create_multipart", "orders_overdue_invoice_list");
        JsonNode library = json(call("GET", "/library", null));
        assertThat(library.path("workspace").asText()).isEqualTo("acme");
        assertThat(library.path("items").findValuesAsText("reference")).contains("mcpgp://acme/naming/acme-standard@2");
    }

    @Test
    void variantsExtendTheirParent() throws Exception {
        // the example variant, uploaded as is
        assertThat(call("POST", "/servers?id=orders-readonly", project(EXAMPLES.resolve("orders-readonly"), Map.of())).statusCode()).isEqualTo(201);
        JsonNode readonly = manifest("orders-readonly");
        assertThat(readonly.path("tools")).filteredOn(t -> t.path("kind").asText().equals("api"))
                .allSatisfy(t -> assertThat(t.at("/annotations/readOnlyHint").asBoolean()).isTrue());
        assertThat(readonly.at("/build/extends/name").asText()).isEqualTo("orders-mcp");

        // made by the platform: latest, and pinned
        JsonNode variant = json(call("POST", "/servers/orders-mcp/variants", JSON.writeValueAsBytes(Map.of("id", "orders-lite",
                "title", "Orders lite"))));
        assertThat(variant.path("parent").asText()).isEqualTo("orders-mcp");
        String definition = new String(call("GET", "/servers/orders-lite/versions/1/files/orders-lite.mcpgp.yaml", null).body(), StandardCharsets.UTF_8);
        assertThat(definition).contains("extends: mcpgp://acme/servers/orders-mcp@latest");
        assertThat(manifest("orders-lite").at("/server/name").asText()).isEqualTo("orders-lite");
        call("POST", "/servers/orders-mcp/variants", JSON.writeValueAsBytes(Map.of("id", "orders-pinned", "pin", true)));
        assertThat(new String(call("GET", "/servers/orders-pinned/versions/1/files/orders-pinned.mcpgp.yaml", null).body(), StandardCharsets.UTF_8))
                .contains("@1");
    }

    @Test
    void duplicatesAreIndependentCopies() throws Exception {
        JsonNode copy = json(call("POST", "/servers/orders-mcp/duplicate", JSON.writeValueAsBytes(Map.of("id", "orders-copy"))));
        assertThat(copy.path("parent").isMissingNode()).isTrue();
        JsonNode original = json(call("GET", "/servers/orders-mcp", null));
        assertThat(copy.at("/versions/0/digest").asText()).isEqualTo(original.at("/versions/0/digest").asText());
        assertThat(manifest("orders-copy").path("tools")).isNotEmpty();
    }

    @Test
    void previewsFollowNewVersionsOfWhatTheyReference() throws Exception {
        call("POST", "/servers/orders-mcp/variants", JSON.writeValueAsBytes(Map.of("id", "orders-follow")));
        String before = manifest("orders-follow").at("/server/instructions").asText();
        Path definition = EXAMPLES.resolve("orders/orders.mcpgp.yaml");
        String changed = Files.readString(definition).replace("Look up and manage customers", "Look up customers");
        assertThat(call("POST", "/servers/orders-mcp/versions", project(EXAMPLES.resolve("orders"), Map.of("orders.mcpgp.yaml", changed)))
                .statusCode()).isEqualTo(201);
        String after = manifest("orders-follow").at("/server/instructions").asText();
        assertThat(before).contains("Look up and manage customers");
        assertThat(after).contains("Look up customers").doesNotContain("and manage");
    }

    @Test
    void libraryInputsAreChecked() throws Exception {
        HttpResponse<byte[]> kind = call("POST", "/library/servers?name=x", files(Map.of("a.yaml", "x")));
        assertThat(kind.statusCode()).isEqualTo(422);
        assertThat(json(kind).path("detail").asText()).contains("servers are referenced from /servers");
        HttpResponse<byte[]> twoFiles = call("POST", "/library/rules?name=r", files(Map.of("a.yaml", "x", "b.yaml", "y")));
        assertThat(json(twoFiles).path("detail").asText()).contains("?root=");
        assertThat(call("POST", "/library/rules?name=r&root=a.yaml", files(Map.of("a.yaml", "x", "b.yaml", "y"))).statusCode()).isEqualTo(201);

        String broken = Files.readString(EXAMPLES.resolve("orders/orders.mcpgp.yaml")).replace("acme-standard@2", "acme-standard@9");
        call("POST", "/servers?id=orders-broken", project(EXAMPLES.resolve("orders"), Map.of("orders.mcpgp.yaml", broken)));
        JsonNode validation = json(call("POST", "/servers/orders-broken/versions/1/validate", null));
        assertThat(validation.path("ok").asBoolean()).isFalse();
        assertThat(validation.path("diagnostics").findValuesAsText("code")).contains("WORKSPACE_REFERENCE_UNRESOLVED");
    }
}
