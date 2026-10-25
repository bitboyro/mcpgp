package ro.bitboy.mcpgp.runtime.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import ro.bitboy.mcpgp.runtime.McpgpServer;
import ro.bitboy.mcpgp.runtime.Startup;
import ro.bitboy.mcpgp.runtime.call.MockTarget;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Calling the API as the signed-in user: their OAuth token exchanged at the identity provider (auth.md §2.5). */
class TokenExchangeTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    static Path dir;
    static HttpServer as;
    static String issuer;
    static RSAKey key;
    static ConfigurableApplicationContext context;
    static MockTarget api;
    static int port;
    static String resource;
    /** The token requests the identity provider got, as form fields, with the client's Authorization. */
    static final List<Map<String, String>> exchanges = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        as = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        issuer = "http://127.0.0.1:" + as.getAddress().getPort();
        as.createContext("/.well-known/oauth-authorization-server", x -> OAuthTest.answer(x, JSON.writeValueAsBytes(Map.of("issuer", issuer,
                "jwks_uri", issuer + "/jwks"))));
        as.createContext("/jwks", x -> OAuthTest.answer(x, new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8)));
        as.createContext("/token", x -> {
            Map<String, String> form = new LinkedHashMap<>();
            for (String pair : new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                String[] kv = pair.split("=", 2);
                form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
            form.put("authorization", x.getRequestHeaders().getFirst("Authorization"));
            exchanges.add(form);
            String subject = form.getOrDefault("subject_token", form.get("assertion"));
            String user;
            try {
                user = SignedJWT.parse(subject).getJWTClaimsSet().getSubject();
            } catch (java.text.ParseException e) {
                throw new java.io.IOException(e);
            }
            OAuthTest.answer(x, JSON.writeValueAsBytes(Map.of("access_token", "api-token-for-" + user + "-" + exchanges.size(),
                    "token_type", "Bearer", "expires_in", 300)));
        });
        as.start();
        api = new MockTarget();
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        resource = "http://127.0.0.1:" + port + "/mcp";

        ObjectNode manifest = (ObjectNode) JSON.readTree(REPO.resolve("examples/manifest/orders-mcp.manifest.json").toFile());
        ((ObjectNode) manifest.at("/apis/0/upstreamAuth")).putObject("tokenExchange");
        Path manifestFile = dir.resolve("orders-mcp.manifest.json");
        JSON.writeValue(manifestFile.toFile(), manifest);
        Path config = dir.resolve("orders-mcp.yaml");
        Files.writeString(config, """
                server:
                  transport: http
                  port: %d
                  publicUrl: http://127.0.0.1:%1$d
                  auth: { mode: oauth, issuer: %2$s }
                instances:
                  - id: prod-eu
                    baseUrl: "%3$s"
                    auth: { type: tokenExchange, tokenUrl: "%2$s/token", clientId: mcp, clientSecret: "${env:EXCHANGE_SECRET}",
                            audience: https://orders.example.com, scopes: [orders.read] }
                  - id: entra
                    baseUrl: "%3$s"
                    auth: { type: tokenExchange, flavor: entra-obo, tokenUrl: "%2$s/token", clientId: mcp, clientSecret: "${env:EXCHANGE_SECRET}",
                            clientAuth: post, scopes: ["api://orders/.default"] }
                """.formatted(port, issuer, api.address()));
        Startup startup = Startup.load(new String[] {"--manifest", manifestFile.toString(), "--config", config.toString(),
                "--storage.root", dir.resolve("data").toString()}, Map.of("EXCHANGE_SECRET", "exchange-secret-1"), dir);
        context = McpgpServer.start(startup, new McpgpServer.Stdio(System.in, System.out, () -> { }));
    }

    @AfterAll
    static void stop() {
        context.close();
        as.stop(0);
        api.close();
    }

    static String userToken(String user) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer).audience(resource).subject(user)
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").type(new JOSEObjectType("at+jwt")).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    static HttpResponse<String> post(String body, String token, String session) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(resource)).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            request.header("Mcp-Session-Id", session).header("Mcp-Protocol-Version", "2025-11-25");
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode get(String user, String instance) throws Exception {
        return getWith(userToken(user), instance);
    }

    /** A call with a user's access token, as their client sends it on every request until it expires. */
    static JsonNode getWith(String token, String instance) throws Exception {
        HttpResponse<String> init = post(HttpServerTest.INITIALIZE, token, null);
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", token, session);
        return HttpServerTest.message(post(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call", "params",
                Map.of("name", "orders_customer_get", "arguments", Map.of("instance", instance, "path", Map.of("id", "c-1"))))), token, session));
    }

    @Test
    void eachUsersSignInIsExchangedForATokenForTheApi() throws Exception {
        exchanges.clear();
        String aliceToken = userToken("alice");
        JsonNode alice = getWith(aliceToken, "prod-eu");
        assertThat(alice.at("/result/isError").asBoolean()).as(alice.toString()).isFalse();
        // RFC 8693, with the server's own client credentials
        Map<String, String> first = exchanges.get(0);
        assertThat(first.get("grant_type")).isEqualTo("urn:ietf:params:oauth:grant-type:token-exchange");
        assertThat(first.get("subject_token_type")).isEqualTo("urn:ietf:params:oauth:token-type:access_token");
        assertThat(first.get("audience")).isEqualTo("https://orders.example.com");
        assertThat(first.get("scope")).isEqualTo("orders.read");
        assertThat(first.get("authorization")).startsWith("Basic ");
        // the API gets the exchanged token, never the user's own
        assertThat(api.last().header("Authorization")).isEqualTo("Bearer api-token-for-alice-1");

        // the same user again: the exchanged token is reused; another user gets their own
        getWith(aliceToken, "prod-eu");
        assertThat(exchanges).hasSize(1);
        get("bob", "prod-eu");
        assertThat(exchanges).hasSize(2);
        assertThat(api.last().header("Authorization")).isEqualTo("Bearer api-token-for-bob-2");

        // Microsoft Entra's on-behalf-of
        JsonNode carol = get("carol", "entra");
        assertThat(carol.at("/result/isError").asBoolean()).as(carol.toString()).isFalse();
        Map<String, String> obo = exchanges.get(2);
        assertThat(obo.get("grant_type")).isEqualTo("urn:ietf:params:oauth:grant-type:jwt-bearer");
        assertThat(obo.get("requested_token_use")).isEqualTo("on_behalf_of");
        assertThat(obo.get("scope")).isEqualTo("api://orders/.default");
        assertThat(obo.get("client_id")).isEqualTo("mcp");
        assertThat(obo.get("assertion")).isNotEmpty();

        String audit = Files.readString(dir.resolve("data/audit/audit.jsonl"));
        assertThat(audit).contains("\"auth\":\"tokenExchange\"").doesNotContain("api-token-for-").doesNotContain("exchange-secret-1");
    }
}
