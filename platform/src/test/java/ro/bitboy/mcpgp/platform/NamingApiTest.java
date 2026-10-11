package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

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
}
