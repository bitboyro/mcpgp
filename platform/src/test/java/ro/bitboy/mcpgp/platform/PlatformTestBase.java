package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
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

import static org.assertj.core.api.Assertions.assertThat;

/** A running platform with an administrator (who has a password), the naming policy and orders-mcp. */
abstract class PlatformTestBase {


    static final Path EXAMPLES = Path.of("..", "examples", "platform").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String PASSWORD = "a long test password";

    @TempDir
    static Path dir;
    static ConfigurableApplicationContext context;
    static String root;
    static String admin;

    @BeforeAll
    static void start() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        PlatformConfig config = new PlatformConfig(dir.resolve("store"), "127.0.0.1", port, null, PlatformConfig.Retention.DEFAULT, "acme");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PlatformServer.init(config, new String[] {"init", "--admin", "ana"}, new PrintStream(out, true, StandardCharsets.UTF_8));
        admin = out.toString(StandardCharsets.UTF_8).lines().filter(l -> l.strip().startsWith("mcpgp_")).findFirst().orElseThrow().strip();
        context = PlatformServer.start(config);
        root = "http://127.0.0.1:" + port;
        String me = json(send(bearer("GET", "/api/v1/me", null))).path("id").asText();
        assertThat(send(bearer("PUT", "/api/v1/identities/" + me + "/password", JSON.writeValueAsBytes(Map.of("password", PASSWORD)))).statusCode())
                .isEqualTo(200);
        assertThat(send(bearer("POST", "/api/v1/library/naming?name=acme-standard",
                JSON.writeValueAsBytes(Map.of("files", Map.of("acme-standard.yaml", "source: operationId\n"))))).statusCode()).isEqualTo(201);
        assertThat(send(bearer("POST", "/api/v1/library/naming/acme-standard/versions", JSON.writeValueAsBytes(Map.of("files",
                Map.of("acme-standard.yaml", Files.readString(EXAMPLES.resolve("workspace/naming/acme-standard.yaml"))))))).statusCode()).isEqualTo(201);
        assertThat(send(bearer("POST", "/api/v1/servers?id=orders-mcp", project(EXAMPLES.resolve("orders")))).statusCode()).isEqualTo(201);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    static HttpRequest.Builder request(String method, String path, byte[] body) {
        return HttpRequest.newBuilder(URI.create(root + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
    }

    static HttpRequest.Builder bearer(String method, String path, byte[] body) {
        return request(method, path, body).header("Authorization", "Bearer " + admin);
    }

    static HttpResponse<byte[]> send(HttpRequest.Builder request) throws Exception {
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static JsonNode json(HttpResponse<byte[]> response) throws Exception {
        return JSON.readTree(response.body());
    }

    static byte[] project(Path folder) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.put(folder.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
        return JSON.writeValueAsBytes(Map.of("files", files));
    }

}
