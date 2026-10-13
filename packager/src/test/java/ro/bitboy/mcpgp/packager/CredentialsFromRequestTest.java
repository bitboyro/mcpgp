package ro.bitboy.mcpgp.packager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A package whose clients may send their own credential for the API (auth.md §2.4). */
class CredentialsFromRequestTest {

    static ObjectNode orders(boolean fromRequest) throws Exception {
        ObjectNode manifest = DeliveryTest.orders();
        if (fromRequest) {
            ((ObjectNode) manifest.at("/apis/0/downstreamAuth")).putObject("fromRequest");
        }
        return manifest;
    }

    static boolean offers(JsonNode schema, String type) {
        for (JsonNode branch : schema.at("/$defs/auth_orders/oneOf")) {
            if (branch.at("/properties/type/const").asText().equals(type)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theConfigSchemaAndTheDocsOfferItOnlyWhenTheDefinitionDoes() throws Exception {
        ObjectNode schema = ConfigSchema.generate(orders(true));
        assertThat(offers(schema, "fromRequest")).isTrue();
        // one credential, or a list tried in order
        assertThat(schema.at("/$defs/instance_orders/properties/auth/oneOf/1/type").asText()).isEqualTo("array");
        Map<String, String> docs = new Docs(orders(true), schema, false).generate(true);
        assertThat(docs.get("docs/CONFIGURATION.md")).contains("## Credentials from the MCP client").contains("X-Orders-Mcp-Authorization");
        assertThat(docs.get("AGENTS.md")).contains("fromRequest");

        ObjectNode without = ConfigSchema.generate(orders(false));
        assertThat(offers(without, "fromRequest")).isFalse();
        assertThat(new Docs(orders(false), without, false).generate(true).get("docs/CONFIGURATION.md")).doesNotContain("Credentials from the MCP client");
    }
}
