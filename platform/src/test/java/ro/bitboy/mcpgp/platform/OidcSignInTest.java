package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Company sign-in with OpenID Connect (spec/platform-api.md §3), against a small identity provider in
 * the test: the authorization code flow with PKCE, from the platform's redirect to its session cookie.
 */
class OidcSignInTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @TempDir
    static Path dir;
    static HttpServer idp;
    static String issuer;
    static RSAKey key;
    /** code → the person the provider says signed in, and the PKCE challenge it was asked with. */
    static final Map<String, Map<String, Object>> codes = new ConcurrentHashMap<>();
    static final Map<String, String> challenges = new ConcurrentHashMap<>();
    static ConfigurableApplicationContext context;
    static String root;
    static String admin;

    @BeforeAll
    static void start() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        idp = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        issuer = "http://127.0.0.1:" + idp.getAddress().getPort();
        idp.createContext("/.well-known/openid-configuration", x -> answer(x, 200, JSON.writeValueAsBytes(Map.of(
                "issuer", issuer, "authorization_endpoint", issuer + "/authorize", "token_endpoint", issuer + "/token",
                "jwks_uri", issuer + "/jwks", "response_types_supported", List.of("code"), "subject_types_supported", List.of("public"),
                "id_token_signing_alg_values_supported", List.of("RS256"), "code_challenge_methods_supported", List.of("S256")))));
        idp.createContext("/jwks", x -> answer(x, 200, new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8)));
        idp.createContext("/token", x -> {
            Map<String, String> form = form(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Map<String, Object> person = codes.remove(form.get("code"));
            String expected = challenges.get(form.get("code"));
            if (person == null || !s256(form.get("code_verifier")).equals(expected)) {
                answer(x, 400, "{\"error\":\"invalid_grant\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            answer(x, 200, JSON.writeValueAsBytes(Map.of("access_token", "at-" + form.get("code"), "token_type", "Bearer", "expires_in", 300,
                    "id_token", idToken(person))));
        });
        idp.start();

        Path secret = dir.resolve("client-secret");
        Files.writeString(secret, "a client secret\n");
        int port;
        try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        Path file = dir.resolve("platform.yaml");
        Files.writeString(file, """
                http: { host: 127.0.0.1, port: %d }
                storage: { root: %s }
                auth:
                  local: { enabled: false }
                  oidc:
                    issuer: %s
                    clientId: mcpgp
                    clientSecret: "${file:%s}"
                    label: Acme SSO
                    roles: { mcpgp-releasers: releaser, mcpgp-admins: admin }
                """.formatted(port, dir.resolve("store"), issuer, secret));
        PlatformConfig config = PlatformConfig.load(file, Map.of());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PlatformServer.init(config, new String[] {"init", "--admin", "ana@acme.com"}, new PrintStream(out, true, StandardCharsets.UTF_8));
        admin = out.toString(StandardCharsets.UTF_8).lines().filter(l -> l.strip().startsWith("mcpgp_")).findFirst().orElseThrow().strip();
        context = PlatformServer.start(config);
        root = "http://127.0.0.1:" + port;
    }

    @AfterAll
    static void stop() {
        context.close();
        idp.stop(0);
    }

    static void answer(com.sun.net.httpserver.HttpExchange x, int status, byte[] body) throws java.io.IOException {
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, body.length);
        x.getResponseBody().write(body);
        x.close();
    }

    static Map<String, String> form(String text) {
        Map<String, String> values = new HashMap<>();
        for (String pair : text.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                values.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    static String s256(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier == null ? new byte[0] : verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String idToken(Map<String, Object> person) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer).audience("mcpgp").subject((String) person.get("sub"))
                    .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)));
            person.forEach((k, v) -> {
                if (!k.equals("sub")) {
                    claims.claim(k, v);
                }
            });
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").type(JOSEObjectType.JWT).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** What a browser does: the platform's redirect, the provider's (here: decided by the test), the callback. Returns the callback's answer. */
    static HttpResponse<byte[]> signIn(Map<String, Object> person) throws Exception {
        HttpResponse<byte[]> start = HTTP.send(HttpRequest.newBuilder(URI.create(root + "/oauth2/authorization/oidc")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(start.statusCode()).isEqualTo(302);
        URI authorize = URI.create(start.headers().firstValue("Location").orElseThrow());
        assertThat(authorize.toString()).startsWith(issuer + "/authorize");
        Map<String, String> query = form(authorize.getRawQuery());
        assertThat(query).containsEntry("response_type", "code").containsEntry("client_id", "mcpgp").containsEntry("code_challenge_method", "S256")
                .containsKeys("state", "nonce", "code_challenge");
        assertThat(query.get("redirect_uri")).isEqualTo(root + "/login/oauth2/code/oidc");
        String session = start.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        String code = "c" + System.nanoTime();
        Map<String, Object> claims = new HashMap<>(person);
        claims.put("nonce", query.get("nonce"));
        codes.put(code, claims);
        challenges.put(code, query.get("code_challenge"));
        return HTTP.send(HttpRequest.newBuilder(URI.create(root + "/login/oauth2/code/oidc?code=" + code + "&state=" + query.get("state")))
                .header("Cookie", session.substring(0, session.indexOf(';'))).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static JsonNode me(String setCookie) throws Exception {
        HttpResponse<byte[]> me = HTTP.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/me"))
                .header("Cookie", setCookie.substring(0, setCookie.indexOf(';'))).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(me.statusCode()).isEqualTo(200);
        return JSON.readTree(me.body());
    }

    static String sessionCookie(HttpResponse<byte[]> callback) {
        return callback.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("mcpgp_session=")).findFirst().orElseThrow();
    }

    @Test
    void aPersonInAMappedGroupGetsTheWebSession() throws Exception {
        HttpResponse<byte[]> callback = signIn(Map.of("sub", "u-bo", "preferred_username", "bo", "email", "bo@acme.com", "email_verified", true,
                "groups", List.of("mcpgp-releasers", "everyone")));
        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("Location").orElse("")).endsWith("/");
        String cookie = sessionCookie(callback);
        assertThat(cookie).contains("HttpOnly", "SameSite=Strict");
        JsonNode me = me(cookie);
        assertThat(me.path("name").asText()).isEqualTo("bo");
        assertThat(me.path("scopes")).extracting(JsonNode::asText).contains("builds:release").doesNotContain("admin");
    }

    @Test
    void theAdministratorIsLinkedByTheirVerifiedEmailOnly() throws Exception {
        // an unverified email doesn't link, even when it matches: the person gets no access instead
        HttpResponse<byte[]> unverified = signIn(Map.of("sub", "u-fake", "preferred_username", "ana@acme.com", "email", "ana@acme.com",
                "email_verified", false, "groups", List.of()));
        assertThat(unverified.headers().firstValue("Location").orElse("")).endsWith("/sign-in?error=no-access");

        HttpResponse<byte[]> callback = signIn(Map.of("sub", "u-ana", "preferred_username", "ana", "email", "ana@acme.com", "email_verified", true));
        JsonNode me = me(sessionCookie(callback));
        assertThat(me.path("name").asText()).isEqualTo("ana@acme.com");
        assertThat(me.path("scopes")).extracting(JsonNode::asText).contains("admin");
    }

    @Test
    void peopleInNoMappedGroupAreSentBackWithoutAnIdentity() throws Exception {
        HttpResponse<byte[]> callback = signIn(Map.of("sub", "u-cy", "preferred_username", "cy", "groups", List.of("everyone")));
        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("Location").orElse("")).endsWith("/sign-in?error=no-access");
        assertThat(callback.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith("mcpgp_session="))).isFalse();
        JsonNode identities = JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/identities"))
                .header("Authorization", "Bearer " + admin).build(), HttpResponse.BodyHandlers.ofByteArray()).body());
        assertThat(identities.path("items").findValuesAsText("name")).doesNotContain("cy");
    }

    @Test
    void aForgedCallbackIsRefused() throws Exception {
        HttpResponse<byte[]> forged = HTTP.send(HttpRequest.newBuilder(URI.create(root + "/login/oauth2/code/oidc?code=x&state=made-up")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(forged.statusCode()).isEqualTo(302);
        assertThat(forged.headers().firstValue("Location").orElse("")).endsWith("/sign-in?error=sso");
        assertThat(forged.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith("mcpgp_session="))).isFalse();
    }

    @Test
    void localAccountsAreOffAndTheSignInPageOffersTheProvider() throws Exception {
        JsonNode options = JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/platform")).build(),
                HttpResponse.BodyHandlers.ofByteArray()).body());
        assertThat(options.at("/auth/oidc").asBoolean()).isTrue();
        assertThat(options.at("/auth/oidcLabel").asText()).isEqualTo("Acme SSO");
        assertThat(options.at("/auth/local").asBoolean()).isFalse();
        HttpResponse<byte[]> password = HTTP.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/sessions")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"ana@acme.com\",\"password\":\"anything at all\"}")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(password.statusCode()).isEqualTo(404);
        // API tokens are unaffected, and API answers don't start Spring sessions
        HttpResponse<byte[]> api = HTTP.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/servers")).header("Authorization", "Bearer " + admin).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(api.statusCode()).isEqualTo(200);
        assertThat(api.headers().allValues("Set-Cookie")).isEmpty();
    }
}
