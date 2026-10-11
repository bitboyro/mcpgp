package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A version's naming (spec/platform-api.md §2.1): the policy, the server's own settings, the effective policy and the lock. */
class NamingApiTest extends PlatformTestBase {

    @Test
    void namingShowsThePolicyWhatTheServerAddsAndTheLock() throws Exception {
        JsonNode naming = json(send(bearer("GET", "/api/v1/servers/orders-mcp/versions/latest/naming", null)));
        assertThat(naming.path("extends").asText()).isEqualTo("mcpgp://acme/naming/acme-standard@2");
        assertThat(naming.at("/own/vocabulary/customer_account").asText()).isEqualTo("customer");
        assertThat(naming.at("/own/source").isMissingNode()).isTrue();
        // the effective policy: the shared one with the server's vocabulary on top
        assertThat(naming.at("/effective/source").asText()).isEqualTo("operationId");
        assertThat(naming.at("/effective/vocabulary/retrieve|fetch|read").asText()).isEqualTo("get");
        assertThat(naming.at("/effective/vocabulary/purchase_order").asText()).isEqualTo("order");
        assertThat(naming.at("/effective/limits/profile").asText()).isEqualTo("strict");
        assertThat(naming.at("/lock/present").asBoolean()).isTrue();
        assertThat(naming.at("/lock/entries/0/id").asText()).isEqualTo("orders:GET:/customers");
        assertThat(naming.at("/lock/entries/0/since").asText()).isEqualTo("1.0.0");
        assertThat(naming.path("version").asInt()).isPositive();
        // lock ids per tool: content-type variants get a suffix
        assertThat(naming.path("tools").findValuesAsText("id")).contains("orders:GET:/customers", "orders:POST:/customers#multipart");
    }

    @Test
    void vocabularyAndPrefixesCanBeEditedOnThePlatform() throws Exception {
        int base = json(send(bearer("GET", "/api/v1/servers/orders-mcp", null))).path("latestVersion").asInt();
        java.util.Map<String, Object> off = new java.util.HashMap<>();
        off.put("op", "set");
        off.put("path", "/naming/vocabulary/retrieve|fetch|read");
        off.put("value", null);
        JsonNode preview = json(send(bearer("POST", "/api/v1/servers/orders-mcp/edits", JSON.writeValueAsBytes(Map.of("base", base, "dryRun", true,
                "changes", List.of(Map.of("op", "set", "path", "/naming/namespace/server", "value", "acme"),
                        Map.of("op", "set", "path", "/naming/vocabulary/account", "value", "acct"), off))))));
        assertThat(preview.path("definition").asText()).contains("namespace: { server: acme }").contains("account: acct").contains("\"retrieve|fetch|read\": null");
        assertThat(preview.has("manifest")).as(preview.path("diagnostics").toString()).isTrue();
        List<String> names = preview.at("/manifest/tools").findValuesAsText("name");
        assertThat(names).contains("acme_orders_acct_list")          // not in the lock file: renamed
                .contains("orders_customer_list");                    // locked: kept
        assertThat(preview.at("/manifest/diagnostics").toString()).contains("NAME_DRIFT");
    }
}
