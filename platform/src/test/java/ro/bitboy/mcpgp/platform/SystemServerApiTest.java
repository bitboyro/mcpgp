package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import ro.bitboy.mcpgp.platform.service.SystemServers;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The platform's own MCP server as a server of the platform: there from the start, read by all, changed by none. */
class SystemServerApiTest extends PlatformTestBase {

    static final String ID = SystemServers.PLATFORM;

    @Test
    void isListedFromTheStartAndCompiles() throws Exception {
        JsonNode list = json(send(bearer("GET", "/api/v1/servers", null)));
        JsonNode platform = null;
        for (JsonNode s : list.path("items")) {
            if (s.path("id").asText().equals(ID)) {
                platform = s;
            }
        }
        assertThat(platform).isNotNull();
        assertThat(platform.path("system").asBoolean()).isTrue();
        assertThat(platform.path("latestVersion").asInt()).isEqualTo(1);

        JsonNode server = json(send(bearer("GET", "/api/v1/servers/" + ID, null)));
        assertThat(server.at("/versions/0/author").asText()).isEqualTo("system");
        JsonNode manifest = json(send(bearer("GET", "/api/v1/servers/" + ID + "/versions/latest/manifest", null)));
        assertThat(manifest.path("tools").findValuesAsText("name")).contains("platform_server_list", "platform_server_edit");
    }

    @Test
    void refusesChangesButMakesVariants() throws Exception {
        int base = json(send(bearer("GET", "/api/v1/servers/" + ID, null))).path("latestVersion").asInt();
        List<HttpResponse<byte[]>> refused = List.of(
                send(bearer("POST", "/api/v1/servers/" + ID + "/edits", JSON.writeValueAsBytes(Map.of("base", base, "dryRun", true,
                        "changes", List.of(Map.of("op", "set", "path", "/exposure/mode", "value", "discovery")))))),
                send(bearer("POST", "/api/v1/servers/" + ID + "/versions", project(EXAMPLES.resolve("orders")))),
                send(bearer("POST", "/api/v1/servers/" + ID + "/archive", null)));
        for (HttpResponse<byte[]> response : refused) {
            assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(409);
            assertThat(json(response).path("code").asText()).isEqualTo("SYSTEM_SERVER");
        }

        HttpResponse<byte[]> variant = send(bearer("POST", "/api/v1/servers/" + ID + "/variants",
                JSON.writeValueAsBytes(Map.of("id", "acme-platform-agents"))));
        assertThat(variant.statusCode()).as(new String(variant.body())).isEqualTo(201);
        assertThat(json(variant).has("system")).isFalse();
        // the variant is a server like any other
        HttpResponse<byte[]> edited = send(bearer("POST", "/api/v1/servers/acme-platform-agents/edits", JSON.writeValueAsBytes(Map.of("base", 1,
                "changes", List.of(Map.of("op", "set", "path", "/overrides/rules/[id=hide-tokens]",
                        "value", Map.of("id", "hide-tokens", "match", Map.of("operationId", "~Token$"), "hidden", true)))))));
        assertThat(edited.statusCode()).as(new String(edited.body())).isEqualTo(201);
        JsonNode tools = json(send(bearer("GET", "/api/v1/servers/acme-platform-agents/versions/latest/manifest", null))).path("tools");
        assertThat(tools.findValuesAsText("name")).contains("platform_server_list").doesNotContain("platform_token_create");
    }

    @Test
    void sharedNoticesForEveryServerLeaveItOut() throws Exception {
        JsonNode applied = json(send(bearer("POST", "/api/v1/library/notices/acme-policy/apply", JSON.writeValueAsBytes(Map.of()))));
        assertThat(applied.path("results").findValuesAsText("server")).doesNotContain(ID);
    }

    @Test
    void tokensSayWhenTheyWereLastUsed() throws Exception {
        JsonNode agent = json(send(bearer("POST", "/api/v1/identities", JSON.writeValueAsBytes(Map.of("kind", "agent", "name", "first-bot",
                "role", "builder")))));
        JsonNode token = json(send(bearer("POST", "/api/v1/tokens", JSON.writeValueAsBytes(Map.of("identity", agent.path("id").asText())))));
        assertThat(lastUsed(token.path("id").asText()).isNull()).isTrue();

        HttpResponse<byte[]> call = send(request("GET", "/api/v1/servers", null).header("Authorization", "Bearer " + token.path("value").asText()));
        assertThat(call.statusCode()).isEqualTo(200);
        assertThat(lastUsed(token.path("id").asText()).asText()).isNotBlank();
    }

    private static JsonNode lastUsed(String token) throws Exception {
        for (JsonNode t : json(send(bearer("GET", "/api/v1/tokens", null))).path("items")) {
            if (t.path("id").asText().equals(token)) {
                return t.path("lastUsed");
            }
        }
        throw new AssertionError("no token " + token);
    }
}
