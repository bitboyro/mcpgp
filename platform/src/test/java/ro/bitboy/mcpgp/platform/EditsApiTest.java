package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Changes made on a server's pages (spec/platform-api.md §2.1, edits).  */
class EditsApiTest extends PlatformTestBase {

    static Map<String, Object> set(String path, Object value) {
        return Map.of("op", "set", "path", path, "value", value);
    }

    static JsonNode latest() throws Exception {
        return json(send(bearer("GET", "/api/v1/servers/orders-mcp", null)));
    }

    @Test
    void aDryRunShowsTheResultAndSavesNothing() throws Exception {
        int base = latest().path("latestVersion").asInt();
        JsonNode preview = json(send(bearer("POST", "/api/v1/servers/orders-mcp/edits", JSON.writeValueAsBytes(Map.of("base", base, "dryRun", true,
                "changes", List.of(set("/exposure/mode", "plain"), set("/apis/[id=orders]/select/access", "read-only")))))));
        assertThat(preview.at("/manifest/exposure/mode").asText()).isEqualTo("plain");
        assertThat(preview.path("exampleConfig").asText()).contains("instances:");
        assertThat(preview.at("/manifest/tools").findValuesAsText("name")).doesNotContain("orders_customer_create");
        assertThat(preview.path("definition").asText()).contains("access: read-only                       # no DELETE tools");
        assertThat(latest().path("latestVersion").asInt()).isEqualTo(base);
    }

    @Test
    void savingMakesTheNextVersionAndKeepsTheFileAsWritten() throws Exception {
        int base = latest().path("latestVersion").asInt();
        HttpResponseLike saved = save(Map.of("base", base, "message", "smaller responses", "changes", List.of(set("/safety/responseLimitKb", 128))));
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(201);
        assertThat(saved.body().path("latestVersion").asInt()).isEqualTo(base + 1);
        assertThat(saved.body().at("/versions").findValuesAsText("message")).contains("smaller responses");
        String file = new String(send(bearer("GET", "/api/v1/servers/orders-mcp/versions/latest/files/orders.mcpgp.yaml", null)).body(),
                StandardCharsets.UTF_8);
        assertThat(file).contains("responseLimitKb: 128").contains("# Platform side: the build definition for orders-mcp.");
        assertThat(json(send(bearer("GET", "/api/v1/servers/orders-mcp/versions/latest/manifest", null))).at("/policies/responseLimitKb").asInt())
                .isEqualTo(128);

        // the same change again on the old base: someone else saved in between
        HttpResponseLike stale = save(Map.of("base", base, "changes", List.of(set("/safety/responseLimitKb", 64))));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().path("detail").asText()).contains("not the latest");
    }

    @Test
    void clientsCredentialsAreTurnedOnAndOffFromConnections() throws Exception {
        int base = latest().path("latestVersion").asInt();
        HttpResponseLike on = save(Map.of("base", base, "changes",
                List.of(set("/apis/[id=orders]/upstreamAuth/fromRequest", Map.of("header", "X-Orders-Authorization")))));
        assertThat(on.status()).as(on.body().toString()).isEqualTo(201);
        JsonNode manifest = json(send(bearer("GET", "/api/v1/servers/orders-mcp/versions/latest/manifest", null)));
        assertThat(manifest.at("/apis/0/upstreamAuth/fromRequest/header").asText()).isEqualTo("X-Orders-Authorization");

        HttpResponseLike off = save(Map.of("base", base + 1, "changes",
                List.of(Map.of("op", "remove", "path", "/apis/[id=orders]/upstreamAuth/fromRequest"))));
        assertThat(off.status()).as(off.body().toString()).isEqualTo(201);
        assertThat(json(send(bearer("GET", "/api/v1/servers/orders-mcp/versions/latest/manifest", null))).at("/apis/0/upstreamAuth")
                .has("fromRequest")).isFalse();
    }

    @Test
    void versionDetectionIsSetAndTurnedOffFromConnections() throws Exception {
        int base = latest().path("latestVersion").asInt();
        HttpResponseLike on = save(Map.of("base", base, "changes", List.of(set("/apis/[id=orders]/detect", Map.of(
                "method", "GET", "path", "/status", "pattern", "^v?(\\d+\\.\\d+)", "header", "X-Api-Version", "onFailure", "refuse")))));
        assertThat(on.status()).as(on.body().toString()).isEqualTo(201);
        JsonNode detect = json(send(bearer("GET", "/api/v1/servers/orders-mcp/versions/latest/manifest", null))).at("/apis/0/detect");
        assertThat(detect.path("header").asText()).isEqualTo("X-Api-Version");
        assertThat(detect.path("onFailure").asText()).isEqualTo("refuse");
        assertThat(detect.has("underBasePath")).as("the whole rule is replaced").isFalse();

        HttpResponseLike off = save(Map.of("base", base + 1, "changes", List.of(Map.of("op", "remove", "path", "/apis/[id=orders]/detect"))));
        assertThat(off.status()).as(off.body().toString()).isEqualTo(201);
        assertThat(json(send(bearer("GET", "/api/v1/servers/orders-mcp/versions/latest/manifest", null))).at("/apis/0").has("detect")).isFalse();
    }

    @Test
    void auditDefaultsShipWithThePackage() throws Exception {
        int base = latest().path("latestVersion").asInt();
        HttpResponseLike saved = save(Map.of("base", base, "changes", List.of(set("/runtimeDefaults/audit/file/retainDays", 90),
                set("/runtimeDefaults/audit/arguments", "hashed"))));
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(201);
        JsonNode audit = json(send(bearer("GET", "/api/v1/servers/orders-mcp/versions/latest/manifest", null))).at("/policies/runtimeDefaults/audit");
        assertThat(audit.at("/file/retainDays").asInt()).isEqualTo(90);
        assertThat(audit.path("arguments").asText()).isEqualTo("hashed");
        // a typo is refused, not ignored
        assertThat(save(Map.of("base", base + 1, "changes", List.of(set("/runtimeDefaults/audit/file/retainDay", 90)))).status()).isEqualTo(422);
    }

    @Test
    void theChangesBetweenVersionsAreShown() throws Exception {
        int base = latest().path("latestVersion").asInt();
        HttpResponseLike saved = save(Map.of("base", base, "message", "read only", "changes", List.of(set("/apis/[id=orders]/select/access", "read-only"),
                set("/exposure/pinned", List.of("orders_customer_get")))));
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(201);
        // default: the version before the latest (no release here), to the latest
        JsonNode diff = json(send(bearer("GET", "/api/v1/servers/orders-mcp/diff", null)));
        assertThat(diff.at("/from/version").asInt()).isEqualTo(base);
        assertThat(diff.at("/to/version").asInt()).isEqualTo(base + 1);
        assertThat(diff.path("breaking").asInt()).isPositive();
        assertThat(diff.path("changes").findValuesAsText("kind")).contains("tool.removed");
        assertThat(diff.path("changes").findValuesAsText("tool")).contains("orders_customer_create");
        assertThat(diff.path("markdown").asText()).startsWith("### orders-mcp: ");
        // the other way round, by number: the tools come back
        JsonNode back = json(send(bearer("GET", "/api/v1/servers/orders-mcp/diff?from=" + (base + 1) + "&to=" + base, null)));
        assertThat(back.path("changes").findValuesAsText("kind")).contains("tool.added").doesNotContain("tool.removed");
        assertThat(send(bearer("GET", "/api/v1/servers/orders-mcp/diff?from=released", null)).statusCode()).isEqualTo(404);
    }

    @Test
    void changesThatBreakTheDefinitionAreNotSaved() throws Exception {
        int base = latest().path("latestVersion").asInt();
        HttpResponseLike broken = save(Map.of("base", base, "changes", List.of(set("/exposure/mode", "everything"))));
        assertThat(broken.status()).isEqualTo(422);
        assertThat(broken.body().path("diagnostics")).isNotEmpty();
        assertThat(latest().path("latestVersion").asInt()).isEqualTo(base);
        assertThat(save(Map.of("base", base, "changes", List.of(Map.of("op", "set", "path", "exposure", "value", 1)))).status()).isEqualTo(422);
    }

    @Test
    void viewersCantEdit() throws Exception {
        JsonNode viewer = json(send(bearer("POST", "/api/v1/identities", JSON.writeValueAsBytes(Map.of("kind", "agent", "name", "look-" + System.nanoTime(),
                "role", "viewer")))));
        String token = json(send(bearer("POST", "/api/v1/tokens", JSON.writeValueAsBytes(Map.of("identity", viewer.path("id").asText()))))).path("value").asText();
        int status = send(request("POST", "/api/v1/servers/orders-mcp/edits", JSON.writeValueAsBytes(Map.of("base", 1, "dryRun", true,
                "changes", List.of(set("/exposure/mode", "plain"))))).header("Authorization", "Bearer " + token)).statusCode();
        assertThat(status).isEqualTo(403);
    }

    record HttpResponseLike(int status, JsonNode body) {
    }

    static HttpResponseLike save(Map<String, Object> body) throws Exception {
        var response = send(bearer("POST", "/api/v1/servers/orders-mcp/edits", JSON.writeValueAsBytes(body)));
        return new HttpResponseLike(response.statusCode(), json(response));
    }
}
