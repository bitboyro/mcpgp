package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Notices and per-tool description overrides through the edits endpoint, as the pages write them. */
class DescriptionsApiTest extends PlatformTestBase {

    static int latest() throws Exception {
        return json(send(bearer("GET", "/api/v1/servers/orders-mcp", null))).path("latestVersion").asInt();
    }

    static JsonNode tool(JsonNode manifest, String name) {
        for (JsonNode t : manifest.path("tools")) {
            if (t.path("name").asText().equals(name)) {
                return t;
            }
        }
        throw new AssertionError("no tool " + name);
    }

    @Test
    void aNoticeAndAToolOverrideAreSavedAsRulesOfTheDefinition() throws Exception {
        int base = latest();
        List<Map<String, Object>> changes = List.of(
                Map.of("op", "set", "path", "/descriptions/notices/[id=policy]", "value",
                        Map.of("id", "policy", "text", "Company policy: customer data stays in the EU.", "match", Map.of("tag", List.of("customers")))),
                Map.of("op", "set", "path", "/overrides/rules/[id=ui:listAccounts]", "value", Map.of("id", "ui:listAccounts",
                        "match", Map.of("operationId", "listAccounts"), "title", Map.of("set", "Accounts"), "description", Map.of("set", "Every account."))));
        var saved = send(bearer("POST", "/api/v1/servers/orders-mcp/edits", JSON.writeValueAsBytes(Map.of("base", base, "changes", changes))));
        assertThat(saved.statusCode()).as(new String(saved.body())).isEqualTo(201);

        JsonNode manifest = json(send(bearer("GET", "/api/v1/servers/orders-mcp/versions/latest/manifest", null)));
        assertThat(tool(manifest, "orders_customer_list").path("description").asText()).endsWith("Company policy: customer data stays in the EU.");
        assertThat(tool(manifest, "orders_account_list").path("title").asText()).isEqualTo("Accounts");
        assertThat(tool(manifest, "orders_account_list").path("description").asText()).isEqualTo("Every account.");

        JsonNode definition = json(send(bearer("GET", "/api/v1/servers/orders-mcp/versions/latest/definition", null)));
        assertThat(definition.at("/root/descriptions/notices/0/id").asText()).isEqualTo("policy");
        assertThat(definition.at("/root/overrides/rules").findValuesAsText("id")).contains("ui:listAccounts");
        assertThat(definition.at("/resolved/naming/vocabulary/retrieve|fetch|read").asText()).isEqualTo("get");   // merged from the policy

        var reset = send(bearer("POST", "/api/v1/servers/orders-mcp/edits", JSON.writeValueAsBytes(Map.of("base", base + 1,
                "changes", List.of(Map.of("op", "remove", "path", "/overrides/rules/[id=ui:listAccounts]"))))));
        assertThat(reset.statusCode()).as(new String(reset.body())).isEqualTo(201);
        JsonNode after = json(send(bearer("GET", "/api/v1/servers/orders-mcp/versions/latest/manifest", null)));
        assertThat(tool(after, "orders_account_list").path("title").asText()).isNotEqualTo("Accounts");
    }
}
