package ro.bitboy.mcpgp.runtime.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import ro.bitboy.mcpgp.runtime.McpgpServer;
import ro.bitboy.mcpgp.runtime.Startup;

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
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code oauth} mode (auth.md §1.1): the server as an OAuth resource server, against a small
 * authorization server in the test (metadata, JWKS, introspection).
 */
class OAuthTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    static Path dir;
    static HttpServer as;
    static String issuer;
    static RSAKey key;
    static ConfigurableApplicationContext context;
    static int port;
    static String resource;
    static final AtomicInteger introspections = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        as = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        issuer = "http://127.0.0.1:" + as.getAddress().getPort();
        as.createContext("/.well-known/oauth-authorization-server", x -> answer(x, JSON.writeValueAsBytes(Map.of("issuer", issuer,
                "jwks_uri", issuer + "/jwks", "introspection_endpoint", issuer + "/introspect"))));
        as.createContext("/jwks", x -> answer(x, new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8)));
        as.createContext("/introspect", x -> {
            introspections.incrementAndGet();
            String form = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean basic = ("Basic " + java.util.Base64.getEncoder().encodeToString("mcp-server:introspect-secret".getBytes(StandardCharsets.UTF_8)))
                    .equals(x.getRequestHeaders().getFirst("Authorization"));
            Map<String, Object> body = form.contains("token=opaque-good") && basic
                    ? Map.of("active", true, "sub", "agent-7", "scope", "orders.read", "aud", resource, "iss", issuer,
                            "exp", Instant.now().plusSeconds(300).getEpochSecond())
                    : Map.of("active", false);
            answer(x, JSON.writeValueAsBytes(body));
        });
        as.start();

        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        resource = "http://127.0.0.1:" + port + "/mcp";
        Path config = dir.resolve("orders-mcp.yaml");
        Files.writeString(config, """
                server:
                  transport: http
                  port: %d
                  publicUrl: http://127.0.0.1:%1$d
                  auth:
                    mode: oauth
                    issuer: %s
                    introspection: { url: "%2$s/introspect", clientId: mcp-server, clientSecret: "${env:INTROSPECT_SECRET}" }
                    scopes:
                      orders.read: { access: read-only }
                      orders.write: { access: read-write }
                instances:
                  - { id: prod-eu, baseUrl: "http://127.0.0.1:1" }
                """.formatted(port, issuer));
        Startup startup = Startup.load(new String[] {"--manifest", REPO.resolve("examples/manifest/orders-mcp.manifest.json").toString(),
                "--config", config.toString(), "--storage.root", dir.resolve("data").toString()}, Map.of("INTROSPECT_SECRET", "introspect-secret"), dir);
        context = McpgpServer.start(startup, new McpgpServer.Stdio(System.in, System.out, () -> { }));
    }

    @AfterAll
    static void stop() {
        context.close();
        as.stop(0);
    }

    static void answer(com.sun.net.httpserver.HttpExchange x, byte[] body) throws java.io.IOException {
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(200, body.length);
        x.getResponseBody().write(body);
        x.close();
    }

    static String token(Map<String, Object> claims) throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder().issuer(issuer).audience(resource).subject("agent-1")
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)));
        claims.forEach(builder::claim);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").type(new JOSEObjectType("at+jwt")).build(), builder.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    static HttpResponse<String> post(String body, String token, String session) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(resource)).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode message(HttpResponse<String> response) throws Exception {
        return HttpServerTest.message(response);
    }

    static String session(String token) throws Exception {
        HttpResponse<String> init = post(HttpServerTest.INITIALIZE, token, null);
        assertThat(init.statusCode()).as(init.body()).isEqualTo(200);
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", token, session);
        return session;
    }

    static String call(String tool, Map<String, Object> arguments) throws Exception {
        return JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 9, "method", "tools/call", "params", Map.of("name", tool, "arguments", arguments)));
    }

    @Test
    void publishesWhereToSignIn() throws Exception {
        HttpResponse<String> metadata = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/.well-known/oauth-protected-resource/mcp")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(metadata.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(metadata.body());
        assertThat(body.path("resource").asText()).isEqualTo(resource);
        assertThat(body.path("authorization_servers")).extracting(JsonNode::asText).containsExactly(issuer);
        assertThat(body.path("scopes_supported")).extracting(JsonNode::asText).containsExactly("orders.read", "orders.write");

        HttpResponse<String> anonymous = post(HttpServerTest.INITIALIZE, null, null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("WWW-Authenticate").orElse(""))
                .isEqualTo("Bearer resource_metadata=\"http://127.0.0.1:" + port + "/.well-known/oauth-protected-resource/mcp\"");
    }

    @Test
    void refusesTokensThatArentForThisServer() throws Exception {
        List<String> bad = List.of(
                token(Map.of("aud", "https://other.example.com/mcp")),                                  // another audience (RFC 8707)
                token(Map.of("iss", "https://evil.example.com")),                                       // another issuer
                token(Map.of("exp", Date.from(Instant.now().minusSeconds(120)))),                       // expired
                new PlainJWT(new JWTClaimsSet.Builder().issuer(issuer).audience(resource).subject("x")
                        .expirationTime(Date.from(Instant.now().plusSeconds(60))).build()).serialize(),  // alg none
                hs256(),                                                                                // a shared-secret algorithm
                "opaque-unknown");                                                                      // introspection: inactive
        for (String token : bad) {
            HttpResponse<String> response = post(HttpServerTest.INITIALIZE, token, null);
            assertThat(response.statusCode()).as(token).isEqualTo(401);
            assertThat(response.headers().firstValue("WWW-Authenticate").orElse("")).contains("error=\"invalid_token\"");
        }
    }

    static String hs256() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().issuer(issuer).audience(resource)
                .subject("x").expirationTime(Date.from(Instant.now().plusSeconds(60))).build());
        jwt.sign(new MACSigner("a-secret-that-is-at-least-thirty-two-bytes-long".getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    @Test
    void scopesDecideWhichToolsAClientSeesAndCalls() throws Exception {
        String reader = token(Map.of("scope", "orders.read"));
        String session = session(reader);
        JsonNode list = message(post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", reader, session));
        List<String> names = list.at("/result/tools").findValuesAsText("name");
        assertThat(names).contains("orders_customer_get", "search_tools", "instances_list").doesNotContain("orders_order_refund");
        assertThat(list.path("id").asInt()).isEqualTo(2);

        HttpResponse<String> write = post(call("orders_order_refund", Map.of("instance", "prod-eu")), reader, session);
        assertThat(write.statusCode()).isEqualTo(403);
        assertThat(write.headers().firstValue("WWW-Authenticate").orElse("")).contains("error=\"insufficient_scope\"", "scope=\"orders.write\"");
        // the same tool through discovery
        HttpResponse<String> indirect = post(call("call_tool", Map.of("instance", "prod-eu", "name", "orders_order_refund", "arguments", Map.of())),
                reader, session);
        assertThat(indirect.statusCode()).isEqualTo(403);

        String writer = token(Map.of("scope", "orders.read orders.write"));
        String second = session(writer);
        assertThat(message(post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}", writer, second)).at("/result/tools")
                .findValuesAsText("name")).contains("orders_order_refund");
        // allowed: the call reaches the tool (whose target here is unreachable, so it answers with an error result, not a 403)
        HttpResponse<String> allowed = post(call("orders_customer_get", Map.of("instance", "prod-eu", "path", Map.of("id", "c-1"))), writer, second);
        assertThat(allowed.statusCode()).isEqualTo(200);
    }

    @Test
    void opaqueTokensAreIntrospectedAndCached() throws Exception {
        int before = introspections.get();
        String session = session("opaque-good");
        assertThat(message(post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}", "opaque-good", session)).at("/result/tools")
                .findValuesAsText("name")).contains("orders_customer_get").doesNotContain("orders_order_refund");
        assertThat(introspections.get() - before).as("one introspection for several requests").isEqualTo(1);
    }

    @Test
    void theClientIsTheTokensSubjectInTheAudit() throws Exception {
        String token = token(Map.of("scope", "orders.read", "sub", "agent-audited"));
        String session = session(token);
        post(call("orders_customer_get", Map.of("instance", "prod-eu", "path", Map.of("id", "c-1"))), token, session);
        Path audit = Files.walk(dir.resolve("data")).filter(p -> p.toString().endsWith(".jsonl")).findFirst().orElseThrow();
        String events = Files.readString(audit);
        assertThat(events).contains("\"id\":\"agent-audited\"").contains("\"auth\":\"oauth\"").doesNotContain(token);
    }
}
