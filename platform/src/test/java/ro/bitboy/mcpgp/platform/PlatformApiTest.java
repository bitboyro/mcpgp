package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import ro.bitboy.mcpgp.platform.store.FileStore;

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
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The platform API end to end (spec/platform-api.md): a real server on a free port, a fresh store. */
class PlatformApiTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Path CASE = REPO.resolve("conformance/cases/content-types/multipart-split/definition");
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    static Path store;
    static ConfigurableApplicationContext context;
    static String base;
    static String admin;

    @BeforeAll
    static void start() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        PlatformConfig config = new PlatformConfig(store, "127.0.0.1", port);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(PlatformServer.init(config, new String[] {"init", "--admin", "ana"}, new PrintStream(out, true, StandardCharsets.UTF_8))).isZero();
        admin = out.toString(StandardCharsets.UTF_8).lines().filter(l -> l.strip().startsWith("mcpgp_")).findFirst().orElseThrow().strip();
        context = PlatformServer.start(config);
        base = "http://127.0.0.1:" + port + "/api/v1";
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    record Response(int status, JsonNode body, HttpResponse<byte[]> raw) {
    }

    static Response call(String method, String path, String token, byte[] body, String contentType, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        headers.forEach(request::header);
        HttpResponse<byte[]> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        String type = response.headers().firstValue("Content-Type").orElse("");
        JsonNode json = type.contains("json") && response.body().length > 0 ? JSON.readTree(response.body()) : null;
        return new Response(response.statusCode(), json, response);
    }

    static Response get(String path, String token) throws Exception {
        return call("GET", path, token, null, null, Map.of());
    }

    static Response post(String path, String token, Object json) throws Exception {
        return call("POST", path, token, json == null ? null : JSON.writeValueAsBytes(json), "application/json", Map.of());
    }

    static byte[] files(Path dir, Map<String, String> changes) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.put(dir.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
        files.putAll(changes);
        return JSON.writeValueAsBytes(Map.of("files", files));
    }

    static byte[] zip(Path dir) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes); Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new ZipEntry("project/" + dir.relativize(p).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(p));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void everythingNeedsAToken() throws Exception {
        Response none = get("/servers", null);
        assertThat(none.status()).isEqualTo(401);
        assertThat(none.body().path("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(get("/servers", "mcpgp_nope_nope").status()).isEqualTo(401);
        // the API's own description is public
        Response openapi = get("/openapi.json", null);
        assertThat(openapi.status()).isEqualTo(200);
        assertThat(openapi.body().path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(get("/me", admin).body().path("name").asText()).isEqualTo("ana");
    }

    @Test
    void serversAndVersions() throws Exception {
        Response created = call("POST", "/servers?id=orders&title=Orders&message=first", admin, zip(CASE), "application/zip", Map.of());
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        assertThat(created.body().path("latestVersion").asInt()).isEqualTo(1);
        assertThat(created.raw().headers().firstValue("ETag")).isPresent();

        // same content again: no new version
        Response same = call("POST", "/servers/orders/versions", admin, files(CASE, Map.of()), "application/json", Map.of());
        assertThat(same.status()).isEqualTo(200);
        assertThat(same.body().path("latestVersion").asInt()).isEqualTo(1);

        String changed = Files.readString(CASE.resolve("orders.mcpgp.yaml")).replace("metadata: { name: orders-mcp }",
                "metadata: { name: orders-mcp, title: Orders v2 }");
        Response v2 = call("POST", "/servers/orders/versions?message=title", admin, files(CASE, Map.of("orders.mcpgp.yaml", changed)),
                "application/json", Map.of());
        assertThat(v2.status()).isEqualTo(201);
        assertThat(v2.body().path("latestVersion").asInt()).isEqualTo(2);
        assertThat(v2.body().at("/versions/1/message").asText()).isEqualTo("title");

        JsonNode version = get("/servers/orders/versions/latest", admin).body();
        assertThat(version.path("root").asText()).isEqualTo("orders.mcpgp.yaml");
        assertThat(version.path("files").findValuesAsText("path")).containsExactly("contracts/orders-2.0.yaml", "orders.mcpgp.yaml");
        Response file = get("/servers/orders/versions/1/files/contracts/orders-2.0.yaml", admin);
        assertThat(new String(file.raw().body(), StandardCharsets.UTF_8)).isEqualTo(Files.readString(CASE.resolve("contracts/orders-2.0.yaml")));

        JsonNode validation = post("/servers/orders/versions/2/validate", admin, null).body();
        assertThat(validation.path("ok").asBoolean()).isTrue();
        JsonNode manifest = get("/servers/orders/versions/2/manifest", admin).body();
        assertThat(manifest.at("/server/title").asText()).isEqualTo("Orders v2");
        assertThat(manifest.path("tools").findValuesAsText("name")).contains("orders_customer_create", "orders_customer_create_multipart");

        assertThat(get("/servers/orders/versions/9", admin).status()).isEqualTo(404);
        assertThat(get("/servers", admin).body().path("items").findValuesAsText("id")).contains("orders");
    }

    @Test
    void uploadsAreChecked() throws Exception {
        Response escape = call("POST", "/servers?id=bad", admin, JSON.writeValueAsBytes(Map.of("files",
                Map.of("a.mcpgp.yaml", "x", "../etc/passwd", "x"))), "application/json", Map.of());
        assertThat(escape.status()).isEqualTo(422);
        assertThat(escape.body().path("detail").asText()).contains("must stay inside the project");
        Response tworoots = call("POST", "/servers?id=bad", admin, JSON.writeValueAsBytes(Map.of("files",
                Map.of("a.mcpgp.yaml", "x", "b.mcpgp.yaml", "y"))), "application/json", Map.of());
        assertThat(tworoots.body().path("detail").asText()).contains("exactly one *.mcpgp.yaml");
        Response badId = call("POST", "/servers?id=Bad_Id", admin, files(CASE, Map.of()), "application/json", Map.of());
        assertThat(badId.status()).isEqualTo(422);

        // a version that doesn't compile: validate says why, the manifest is refused
        assertThat(call("POST", "/servers?id=broken", admin, JSON.writeValueAsBytes(Map.of("files",
                Map.of("broken.mcpgp.yaml", "apiVersion: mcpgp/v1\nkind: McpServer\n"))), "application/json", Map.of()).status()).isEqualTo(201);
        JsonNode validation = post("/servers/broken/versions/1/validate", admin, null).body();
        assertThat(validation.path("ok").asBoolean()).isFalse();
        assertThat(validation.path("diagnostics")).isNotEmpty();
        Response manifest = get("/servers/broken/versions/1/manifest", admin);
        assertThat(manifest.status()).isEqualTo(422);
        assertThat(manifest.body().path("diagnostics")).isNotEmpty();
    }

    @Test
    void archiveUsesOptimisticConcurrency() throws Exception {
        Response created = call("POST", "/servers?id=notes", admin, files(CASE, Map.of()), "application/json", Map.of());
        String etag = created.raw().headers().firstValue("ETag").orElseThrow();
        Response stale = call("POST", "/servers/notes/archive", admin, null, null, Map.of("If-Match", "\"999\""));
        assertThat(stale.status()).isEqualTo(412);
        assertThat(call("POST", "/servers/notes/archive", admin, null, null, Map.of("If-Match", etag)).status()).isEqualTo(200);
        assertThat(get("/servers", admin).body().path("items").findValuesAsText("id")).doesNotContain("notes");
        assertThat(get("/servers?archived=true", admin).body().path("items").findValuesAsText("id")).contains("notes");
        Response blocked = call("POST", "/servers/notes/versions", admin, files(CASE, Map.of("x.txt", "1")), "application/json", Map.of());
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(post("/servers/notes/restore", admin, null).status()).isEqualTo(200);
    }

    @Test
    void identitiesTokensAndScopes() throws Exception {
        Response agent = post("/identities", admin, Map.of("kind", "agent", "name", "build-bot", "role", "builder"));
        assertThat(agent.status()).isEqualTo(201);
        Response token = post("/tokens", admin, Map.of("identity", agent.body().path("id").asText(), "label", "ci"));
        String value = token.body().path("value").asText();
        assertThat(value).startsWith("mcpgp_");

        // the agent can work on servers, not on identities
        assertThat(call("POST", "/servers?id=bot-made", value, files(CASE, Map.of()), "application/json", Map.of()).status()).isEqualTo(201);
        Response forbidden = get("/identities", value);
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.body().path("detail").asText()).contains("admin");
        // tokens can't widen scopes
        assertThat(post("/tokens", value, Map.of("scopes", java.util.List.of("admin"))).status()).isEqualTo(422);

        // revoked tokens stop working
        assertThat(call("DELETE", "/tokens/" + token.body().path("id").asText(), admin, null, null, Map.of()).status()).isEqualTo(200);
        assertThat(get("/servers", value).status()).isEqualTo(401);

        JsonNode activity = get("/activity?limit=200", admin).body();
        assertThat(activity.path("items").findValuesAsText("action")).contains("identity.create", "token.create", "token.revoke", "server.create");
        assertThat(activity.path("items").findValuesAsText("actor")).contains("build-bot", "ana");
        assertThat(activity.toString()).doesNotContain(value.substring("mcpgp_".length()));
        assertThat(Files.readString(store.resolve("identities/tokens.json"))).doesNotContain(value.substring(value.lastIndexOf('_') + 1));
    }

    @Test
    void storesOfAnotherFormatAreRefused(@TempDir Path other) throws IOException {
        Files.writeString(other.resolve("store.json"), "{ \"format\": \"0.0\" }");
        assertThatThrownBy(() -> new FileStore(other)).hasMessageContaining("has format 0.0").hasMessageContaining("export");
        Path notEmpty = Files.createDirectories(other.resolve("x"));
        Files.writeString(notEmpty.resolve("file"), "x");
        assertThatThrownBy(() -> new FileStore(notEmpty)).hasMessageContaining("not empty");
    }
}
