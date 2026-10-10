package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import ro.bitboy.mcpgp.packager.Minisign;

import java.io.ByteArrayInputStream;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Builds on the platform (spec/platform-api.md §2.2): queue, package, sign, download, release, delete. */
class BuildsApiTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Path CASE = REPO.resolve("conformance/cases/content-types/multipart-split/definition");
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    static Path dir;
    static ConfigurableApplicationContext context;
    static String base;
    static String admin;
    static Minisign.Keys keys;

    @BeforeAll
    static void start() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        keys = Minisign.generate();
        Path key = dir.resolve("signing.key");
        Files.writeString(key, Minisign.secretKeyFile(keys));
        PlatformConfig config = new PlatformConfig(dir.resolve("store"), "127.0.0.1", port, "${file:" + key + "}",
                PlatformConfig.Retention.DEFAULT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(PlatformServer.init(config, new String[] {"init", "--admin", "ana"}, new PrintStream(out, true, StandardCharsets.UTF_8))).isZero();
        admin = out.toString(StandardCharsets.UTF_8).lines().filter(l -> l.strip().startsWith("mcpgp_")).findFirst().orElseThrow().strip();
        context = PlatformServer.start(config);
        base = "http://127.0.0.1:" + port + "/api/v1";
        assertThat(call("POST", "/servers?id=orders", admin, project(Map.of()), "application/json").statusCode()).isEqualTo(201);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    static HttpResponse<byte[]> call(String method, String path, String token, byte[] body, String type) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Authorization", "Bearer " + token);
        if (type != null) {
            request.header("Content-Type", type);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static JsonNode json(HttpResponse<byte[]> response) throws IOException {
        return JSON.readTree(response.body());
    }

    static byte[] project(Map<String, String> changes) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(CASE)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.put(CASE.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
        files.putAll(changes);
        return JSON.writeValueAsBytes(Map.of("files", files));
    }

    /** Starts a build and waits until it is done. */
    static JsonNode build(String server, Object request) throws Exception {
        HttpResponse<byte[]> started = call("POST", "/servers/" + server + "/builds", admin, JSON.writeValueAsBytes(request), "application/json");
        assertThat(started.statusCode()).as(new String(started.body())).isEqualTo(202);
        String id = json(started).path("id").asText();
        for (int i = 0; i < 300; i++) {
            JsonNode build = json(call("GET", "/builds/" + id, admin, null, null));
            if (!build.path("status").asText().equals("queued") && !build.path("status").asText().equals("running")) {
                return build;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("build " + id + " did not finish");
    }

    static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), in.readAllBytes());
            }
        }
        return files;
    }

    @Test
    void buildsASignedPackage() throws Exception {
        JsonNode build = build("orders", Map.of());
        assertThat(build.path("status").asText()).as(new String(call("GET", "/builds/" + build.path("id").asText() + "/log", admin, null, null).body()))
                .isEqualTo("succeeded");
        String id = build.path("id").asText();
        assertThat(build.path("artifacts").findValuesAsText("name")).containsExactly("orders-mcp-2.0.0.zip", "orders-mcp-2.0.0.manifest.json");

        JsonNode artifacts = json(call("GET", "/builds/" + id + "/artifacts", admin, null, null));
        assertThat(artifacts.path("packageFiles").findValuesAsText("path")).contains("orders-mcp.jar", "SHA256SUMS.sig", "SIGNING-KEY.pub");

        byte[] zip = call("GET", "/builds/" + id + "/artifacts/orders-mcp-2.0.0.zip", admin, null, null).body();
        Map<String, byte[]> files = unzip(zip);
        String trusted = Minisign.verify(Minisign.readPublicKey(Minisign.publicKeyFile(keys)), files.get("SHA256SUMS"),
                new String(files.get("SHA256SUMS.sig"), StandardCharsets.UTF_8));
        assertThat(trusted).contains("server:orders-mcp-2.0.0");

        HttpResponse<byte[]> tools = call("GET", "/builds/" + id + "/artifacts/orders-mcp-2.0.0.zip?file=docs/TOOLS.md", admin, null, null);
        assertThat(new String(tools.body(), StandardCharsets.UTF_8)).contains("orders_customer_create_multipart");
        assertThat(tools.headers().firstValue("Content-Disposition").orElse("")).contains("TOOLS.md");
        String log = new String(call("GET", "/builds/" + id + "/log", admin, null, null).body(), StandardCharsets.UTF_8);
        assertThat(log).contains("building orders version 1").contains("(signed)").contains("succeeded");
        assertThat(json(call("GET", "/activity?action=artifact.download", admin, null, null)).path("items")).isNotEmpty();
    }

    @Test
    void rebuildsGetABuildNumber() throws Exception {
        JsonNode first = build("orders", Map.of("version", "latest"));
        JsonNode second = build("orders", Map.of("version", "latest"));
        assertThat(second.path("serverVersion").asText()).matches("2\\.0\\.0\\+\\d+");
        assertThat(second.path("serverVersion").asText()).isNotEqualTo(first.path("serverVersion").asText());
        assertThat(build("orders", Map.of("buildNumber", 7)).path("serverVersion").asText()).isEqualTo("2.0.0+7");
    }

    @Test
    void brokenDefinitionsFailWithALog() throws Exception {
        assertThat(call("POST", "/servers?id=broken", admin, JSON.writeValueAsBytes(Map.of("files",
                Map.of("broken.mcpgp.yaml", "apiVersion: mcpgp/v1\nkind: McpServer\n"))), "application/json").statusCode()).isEqualTo(201);
        JsonNode build = build("broken", Map.of());
        assertThat(build.path("status").asText()).isEqualTo("failed");
        assertThat(build.path("error").asText()).contains("does not compile");
        assertThat(new String(call("GET", "/builds/" + build.path("id").asText() + "/log", admin, null, null).body(), StandardCharsets.UTF_8))
                .contains("DEFINITION_INVALID");
    }

    @Test
    void releaseArchiveAndDeleteFollowTheRules() throws Exception {
        String id = build("orders", Map.of()).path("id").asText();
        // agents can't release directly: their release becomes an approval (AccountsTest covers deciding it)
        JsonNode agent = json(call("POST", "/identities", admin, JSON.writeValueAsBytes(Map.of("kind", "agent", "name", "rel-bot",
                "role", "releaser")), "application/json"));
        String agentToken = json(call("POST", "/tokens", admin, JSON.writeValueAsBytes(Map.of("identity", agent.path("id").asText())),
                "application/json")).path("value").asText();
        HttpResponse<byte[]> byAgent = call("POST", "/builds/" + id + "/release", agentToken, null, null);
        assertThat(byAgent.statusCode()).isEqualTo(202);
        assertThat(json(byAgent).at("/approval/status").asText()).isEqualTo("pending");
        assertThat(json(call("GET", "/builds/" + id, admin, null, null)).path("released").asBoolean()).isFalse();
        assertThat(call("DELETE", "/builds/" + id, agentToken, null, null).statusCode()).isEqualTo(403);

        JsonNode released = json(call("POST", "/builds/" + id + "/release", admin, null, null));
        assertThat(released.path("released").asBoolean()).isTrue();
        assertThat(released.path("releasedBy").asText()).isEqualTo("ana");

        // released builds must be archived before deletion
        HttpResponse<byte[]> early = call("DELETE", "/builds/" + id, admin, null, null);
        assertThat(early.statusCode()).isEqualTo(409);
        assertThat(json(call("POST", "/builds/" + id + "/archive", admin, null, null)).path("archived").asBoolean()).isTrue();
        JsonNode deleted = json(call("DELETE", "/builds/" + id, admin, null, null));
        assertThat(deleted.path("deleted").asBoolean()).isTrue();
        assertThat(deleted.path("artifacts")).isEmpty();
        assertThat(call("GET", "/builds/" + id + "/artifacts/orders-mcp-2.0.0.zip", admin, null, null).statusCode()).isEqualTo(404);
        // the record stays
        assertThat(json(call("GET", "/builds/" + id, admin, null, null)).path("released").asBoolean()).isTrue();
        assertThat(json(call("GET", "/servers/orders/builds?archived=true", admin, null, null)).path("items").findValuesAsText("id")).contains(id);
    }
}
