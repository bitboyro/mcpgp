package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Local accounts, approvals, agent quotas, export and import (spec/platform-api.md §3, §5, §7.1). */
class AccountsTest {

    static final Path CASE = Path.of("..", "conformance/cases/content-types/multipart-split/definition").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    static Path dir;

    /** A running platform for tests. */
    record Platform(ConfigurableApplicationContext context, String base, String admin) {
        HttpResponse<byte[]> call(String method, String path, String token, byte[] body, String type) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            if (type != null) {
                request.header("Content-Type", type);
            }
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        }

        JsonNode json(String method, String path, String token, Object body) throws Exception {
            HttpResponse<byte[]> response = call(method, path, token, body == null ? null : JSON.writeValueAsBytes(body), "application/json");
            return response.body().length == 0 ? JSON.createObjectNode() : JSON.readTree(response.body());
        }
    }

    static Platform platform;

    static Platform start(Path store, PlatformConfig.Guardrails guardrails) throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        PlatformConfig config = TestStores.of(new PlatformConfig(store, "127.0.0.1", port, null, PlatformConfig.Retention.DEFAULT, "local", true, guardrails));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PlatformServer.init(config, new String[] {"init", "--admin", "ana"}, new PrintStream(out, true, StandardCharsets.UTF_8));
        String admin = out.toString(StandardCharsets.UTF_8).lines().filter(l -> l.strip().startsWith("mcpgp_")).findFirst().orElseThrow().strip();
        return new Platform(PlatformServer.start(config), "http://127.0.0.1:" + port + "/api/v1", admin);
    }

    @BeforeAll
    static void startPlatform() throws IOException {
        platform = start(dir.resolve("store"), new PlatformConfig.Guardrails(2, 3));
    }

    @AfterAll
    static void stop() {
        platform.context().close();
    }

    static byte[] project() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(CASE)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.put(CASE.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
        return JSON.writeValueAsBytes(Map.of("files", files));
    }

    static JsonNode waitForBuild(Platform p, String token, String id) throws Exception {
        for (int i = 0; i < 300; i++) {
            JsonNode build = p.json("GET", "/builds/" + id, token, null);
            if (build.path("status").asText().matches("succeeded|failed")) {
                return build;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("build did not finish");
    }

    @Test
    void localAccountsSignInAndLockAfterFailures() throws Exception {
        JsonNode bob = platform.json("POST", "/identities", platform.admin(), Map.of("kind", "person", "name", "bob", "role", "viewer",
                "password", "correct horse battery"));
        assertThat(bob.path("kind").asText()).isEqualTo("person");

        JsonNode session = platform.json("POST", "/sessions", null, Map.of("name", "bob", "password", "correct horse battery"));
        assertThat(session.path("label").asText()).isEqualTo("session");
        assertThat(platform.json("GET", "/me", session.path("value").asText(), null).path("name").asText()).isEqualTo("bob");

        HttpResponse<byte[]> wrong = platform.call("POST", "/sessions", null, JSON.writeValueAsBytes(Map.of("name", "bob", "password", "nope")), "application/json");
        HttpResponse<byte[]> unknown = platform.call("POST", "/sessions", null, JSON.writeValueAsBytes(Map.of("name", "nobody", "password", "nope")), "application/json");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(wrong.body()).path("detail")).isEqualTo(JSON.readTree(unknown.body()).path("detail"));

        for (int i = 0; i < 5; i++) {
            platform.call("POST", "/sessions", null, JSON.writeValueAsBytes(Map.of("name", "bob", "password", "still wrong")), "application/json");
        }
        // locked: even the right password is refused for a while
        assertThat(platform.call("POST", "/sessions", null, JSON.writeValueAsBytes(Map.of("name", "bob", "password", "correct horse battery")),
                "application/json").statusCode()).isEqualTo(401);
        assertThat(platform.json("GET", "/activity?action=signin", platform.admin(), null).path("items").findValuesAsText("outcome")).contains("denied", "ok");
        if (TestStores.URL == null) {   // what the file store writes; the same records go to PostgreSQL as JSON
            assertThat(Files.readString(dir.resolve("store/identities/credentials.json"))).doesNotContain("correct horse");
        }
    }

    @Test
    void passwordsAreChangedWithTheCurrentOneAndOnlyForPeople() throws Exception {
        JsonNode carol = platform.json("POST", "/identities", platform.admin(), Map.of("kind", "person", "name", "carol", "role", "viewer",
                "password", "first password 1"));
        String token = platform.json("POST", "/sessions", null, Map.of("name", "carol", "password", "first password 1")).path("value").asText();
        String id = carol.path("id").asText();
        assertThat(platform.call("PUT", "/identities/" + id + "/password", token, JSON.writeValueAsBytes(Map.of("password", "second password 2",
                "currentPassword", "wrong")), "application/json").statusCode()).isEqualTo(403);
        assertThat(platform.call("PUT", "/identities/" + id + "/password", token, JSON.writeValueAsBytes(Map.of("password", "short",
                "currentPassword", "first password 1")), "application/json").statusCode()).isEqualTo(422);
        assertThat(platform.call("PUT", "/identities/" + id + "/password", token, JSON.writeValueAsBytes(Map.of("password", "second password 2",
                "currentPassword", "first password 1")), "application/json").statusCode()).isEqualTo(200);
        assertThat(platform.call("POST", "/sessions", null, JSON.writeValueAsBytes(Map.of("name", "carol", "password", "second password 2")),
                "application/json").statusCode()).isEqualTo(201);
        assertThat(platform.call("POST", "/identities", platform.admin(), JSON.writeValueAsBytes(Map.of("kind", "agent", "name", "pw-bot",
                "password", "agents have none")), "application/json").statusCode()).isEqualTo(422);
    }

    @Test
    void agentReleasesNeedAPersonsApproval() throws Exception {
        String admin = platform.admin();
        platform.call("POST", "/servers?id=orders", admin, project(), "application/json");
        JsonNode agent = platform.json("POST", "/identities", admin, Map.of("kind", "agent", "name", "release-bot", "role", "releaser"));
        String bot = platform.json("POST", "/tokens", admin, Map.of("identity", agent.path("id").asText())).path("value").asText();
        String build = waitForBuild(platform, bot, platform.json("POST", "/servers/orders/builds", bot, Map.of()).path("id").asText())
                .path("id").asText();

        JsonNode requested = platform.json("POST", "/builds/" + build + "/release", bot, null);
        String approval = requested.at("/approval/id").asText();
        assertThat(platform.json("GET", "/approvals", admin, null).path("items").findValuesAsText("id")).contains(approval);
        // the agent can't decide, even with approvals:decide in its role
        assertThat(platform.call("POST", "/approvals/" + approval + "/approve", bot, null, null).statusCode()).isEqualTo(403);
        JsonNode decided = platform.json("POST", "/approvals/" + approval + "/approve", admin, Map.of("comment", "looks good"));
        assertThat(decided.path("status").asText()).isEqualTo("approved");
        assertThat(decided.path("decidedBy").asText()).isEqualTo("ana");
        assertThat(platform.json("GET", "/builds/" + build, admin, null).path("released").asBoolean()).isTrue();
        assertThat(platform.call("POST", "/approvals/" + approval + "/reject", admin, null, null).statusCode()).isEqualTo(409);
    }

    @Test
    void agentQuotas() throws Exception {
        String admin = platform.admin();
        JsonNode agent = platform.json("POST", "/identities", admin, Map.of("kind", "agent", "name", "busy-bot", "role", "builder"));
        String bot = platform.json("POST", "/tokens", admin, Map.of("identity", agent.path("id").asText())).path("value").asText();
        // 3 versions per hour (servers count as versions)
        for (int i = 1; i <= 3; i++) {
            assertThat(platform.call("POST", "/servers?id=busy-" + i, bot, project(), "application/json").statusCode()).isEqualTo(201);
        }
        HttpResponse<byte[]> over = platform.call("POST", "/servers?id=busy-4", bot, project(), "application/json");
        assertThat(over.statusCode()).isEqualTo(429);
        assertThat(JSON.readTree(over.body()).path("code").asText()).isEqualTo("QUOTA_EXCEEDED");
        // people have no quota
        assertThat(platform.call("POST", "/servers?id=busy-4", admin, project(), "application/json").statusCode()).isEqualTo(201);
    }

    @Test
    void exportAndImportMoveEverythingToAFreshPlatform(@TempDir Path other) throws Exception {
        String admin = platform.admin();
        platform.call("POST", "/servers?id=moving", admin, project(), "application/json");
        String build = waitForBuild(platform, admin, platform.json("POST", "/servers/moving/builds", admin, Map.of()).path("id").asText())
                .path("id").asText();
        byte[] export = platform.call("GET", "/export?artifacts=true", admin, null, null).body();

        Platform fresh = start(other.resolve("store"), PlatformConfig.Guardrails.DEFAULT);
        try {
            // a tampered blob is refused
            HttpResponse<byte[]> tampered = fresh.call("POST", "/import", fresh.admin(), tamper(export), "application/zip");
            assertThat(tampered.statusCode()).isEqualTo(422);

            JsonNode counts = JSON.readTree(fresh.call("POST", "/import", fresh.admin(), export, "application/zip").body());
            assertThat(counts.path("servers").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(fresh.json("GET", "/servers/moving/versions/latest/manifest", fresh.admin(), null).path("tools")).isNotEmpty();
            assertThat(fresh.json("GET", "/builds/" + build, fresh.admin(), null).path("status").asText()).isEqualTo("succeeded");
            assertThat(fresh.call("GET", "/builds/" + build + "/artifacts/orders-mcp-2.0.0.zip", fresh.admin(), null, null).statusCode())
                    .isEqualTo(200);
            // the fresh platform's administrator stays; imported people can sign in where they had passwords
            assertThat(fresh.json("GET", "/me", fresh.admin(), null).path("name").asText()).isEqualTo("ana");
            // importing again is refused: import fills a fresh store
            assertThat(fresh.call("POST", "/import", fresh.admin(), export, "application/zip").statusCode()).isEqualTo(409);
        } finally {
            fresh.context().close();
        }
    }

    static byte[] tamper(byte[] zip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip)); ZipOutputStream copy = new ZipOutputStream(out)) {
            ZipEntry entry;
            boolean changed = false;
            while ((entry = in.getNextEntry()) != null) {
                byte[] content = in.readAllBytes();
                if (!changed && entry.getName().startsWith("blobs/")) {
                    content = "changed".getBytes(StandardCharsets.UTF_8);
                    changed = true;
                }
                copy.putNextEntry(new ZipEntry(entry.getName()));
                copy.write(content);
                copy.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
