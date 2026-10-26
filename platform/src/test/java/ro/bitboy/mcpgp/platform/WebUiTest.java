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

/**
 * The web UI's side of the platform (M6): pages and their headers, the session cookie, the guard on
 * changes made with it, sign-out, and the read endpoints the pages use.
 */
class WebUiTest extends PlatformTestBase {

    /** Signs in as the web UI does; returns the Set-Cookie header. */
    static String webSignIn() throws Exception {
        HttpResponse<byte[]> response = send(request("POST", "/api/v1/sessions", JSON.writeValueAsBytes(Map.of("name", "ana", "password", PASSWORD)))
                .header("X-Mcpgp-Client", "web"));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response).has("value")).as("the token stays out of the page's reach").isFalse();
        return response.headers().firstValue("Set-Cookie").orElseThrow();
    }

    static String cookie(String setCookie) {
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    @Test
    void pagesComeWithTheirSecurityHeaders() throws Exception {
        for (String path : new String[] {"/", "/sign-in", "/servers/orders-mcp/tools", "/agents"}) {
            HttpResponse<byte[]> page = send(request("GET", path, null));
            assertThat(page.statusCode()).as(path).isEqualTo(200);
            assertThat(page.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        }
        HttpResponse<byte[]> page = send(request("GET", "/", null));
        assertThat(page.headers().firstValue("Content-Security-Policy").orElse("")).contains("default-src 'self'", "frame-ancestors 'none'")
                .doesNotContain("unsafe-inline");
        assertThat(page.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(page.headers().firstValue("Cache-Control")).contains("no-cache");
        // API answers get the same headers
        assertThat(send(request("GET", "/api/v1/platform", null)).headers().firstValue("X-Frame-Options")).contains("DENY");
    }

    @Test
    void theSessionIsAnHttpOnlyStrictCookie() throws Exception {
        String setCookie = webSignIn();
        assertThat(setCookie).startsWith("mcpgp_session=mcpgp_").contains("HttpOnly", "SameSite=Strict", "Path=/", "Max-Age=43200");
        HttpResponse<byte[]> me = send(request("GET", "/api/v1/me", null).header("Cookie", cookie(setCookie)));
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json(me).path("name").asText()).isEqualTo("ana");
        // API clients still get the token in the body
        HttpResponse<byte[]> api = send(request("POST", "/api/v1/sessions", JSON.writeValueAsBytes(Map.of("name", "ana", "password", PASSWORD))));
        assertThat(json(api).path("value").asText()).startsWith("mcpgp_");
        assertThat(api.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void changesWithTheCookieOnlyComeFromThePlatformsOwnPages() throws Exception {
        String session = cookie(webSignIn());
        byte[] body = JSON.writeValueAsBytes(Map.of("id", "orders-copy"));
        // a form or script on another site: no client header
        HttpResponse<byte[]> forged = send(request("POST", "/api/v1/servers/orders-mcp/duplicate", body).header("Cookie", session));
        assertThat(forged.statusCode()).isEqualTo(403);
        assertThat(json(forged).path("code").asText()).isEqualTo("FORBIDDEN");
        // the header, but sent from another origin
        assertThat(send(request("POST", "/api/v1/servers/orders-mcp/duplicate", body).header("Cookie", session)
                .header("X-Mcpgp-Client", "web").header("Origin", "https://elsewhere.example")).statusCode()).isEqualTo(403);
        // the platform's own page
        assertThat(send(request("POST", "/api/v1/servers/orders-mcp/duplicate", body).header("Cookie", session)
                .header("X-Mcpgp-Client", "web").header("Origin", root)).statusCode()).isEqualTo(201);
        // bearer tokens are not affected
        assertThat(send(bearer("POST", "/api/v1/servers/orders-mcp/duplicate", JSON.writeValueAsBytes(Map.of("id", "orders-copy-2")))).statusCode())
                .isEqualTo(201);
    }

    @Test
    void signingOutEndsTheSession() throws Exception {
        String session = cookie(webSignIn());
        HttpResponse<byte[]> out = send(request("DELETE", "/api/v1/sessions/current", null).header("Cookie", session).header("X-Mcpgp-Client", "web"));
        assertThat(out.statusCode()).isEqualTo(200);
        assertThat(out.headers().firstValue("Set-Cookie").orElse("")).startsWith("mcpgp_session=;").contains("Max-Age=0");
        assertThat(send(request("GET", "/api/v1/me", null).header("Cookie", session)).statusCode()).isEqualTo(401);
        // an API token is revoked as a token, not signed out
        HttpResponse<byte[]> token = send(bearer("DELETE", "/api/v1/sessions/current", null));
        assertThat(token.statusCode()).isEqualTo(409);
        assertThat(send(bearer("GET", "/api/v1/me", null)).statusCode()).isEqualTo(200);
        JsonNode activity = json(send(bearer("GET", "/api/v1/activity?action=signout", null)));
        assertThat(activity.path("items")).isNotEmpty();
    }

    @Test
    void signInOptionsNeedNoTokenAndSettingsDo() throws Exception {
        HttpResponse<byte[]> options = send(request("GET", "/api/v1/platform", null));
        assertThat(options.statusCode()).isEqualTo(200);
        assertThat(json(options).at("/auth/local").asBoolean()).isTrue();
        assertThat(json(options).has("workspace")).isFalse();
        assertThat(send(request("GET", "/api/v1/settings", null)).statusCode()).isEqualTo(401);
        JsonNode settings = json(send(bearer("GET", "/api/v1/settings", null)));
        assertThat(settings.path("workspace").asText()).isEqualTo("acme");
        assertThat(settings.at("/retention/keepLast").asInt()).isEqualTo(10);
        assertThat(settings.at("/signing/configured").asBoolean()).isFalse();
        // the platform's own build: the recorded version and build time, never the time it started
        assertThat(settings.at("/build/version").asText()).matches("\\d+\\.\\d+\\.\\d+.*");
        java.time.Instant built = java.time.Instant.parse(settings.at("/build/time").asText());
        assertThat(built).isBefore(java.time.Instant.ofEpochMilli(java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime()));
        assertThat(settings.at("/build/java").asText()).startsWith(System.getProperty("java.version"));
        assertThat(settings.at("/guardrails/agentBuildsPerHour").asInt()).isEqualTo(20);
    }

    @Test
    void theExampleConfigIsWhatThePackageShips() throws Exception {
        HttpResponse<byte[]> example = send(bearer("GET", "/api/v1/servers/orders-mcp/versions/latest/example-config", null));
        assertThat(example.statusCode()).isEqualTo(200);
        String yaml = new String(example.body(), StandardCharsets.UTF_8);
        assertThat(yaml).contains("# orders-mcp 2.0.0 — example configuration.", "instances:", "${env:");
    }

    @Test
    void activityCanBeFilteredByServerIncludingItsBuilds() throws Exception {
        JsonNode build = json(send(bearer("POST", "/api/v1/servers/orders-mcp/builds", "{}".getBytes(StandardCharsets.UTF_8))));
        String id = build.path("id").asText();
        JsonNode items = json(send(bearer("GET", "/api/v1/activity?server=orders-mcp", null))).path("items");
        assertThat(items.findValuesAsText("action")).contains("server.create", "build.start");
        assertThat(items.findValuesAsText("target")).contains("orders-mcp", id).doesNotContain("acme-standard");
    }
}
