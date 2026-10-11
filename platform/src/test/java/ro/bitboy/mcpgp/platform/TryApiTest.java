package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Trying tools against a mock of the API (spec/platform-api.md §2.1): what the agent gets, what would be sent. */
class TryApiTest extends PlatformTestBase {

    static JsonNode tryTool(Object body) throws Exception {
        return json(send(bearer("POST", "/api/v1/servers/orders-mcp/versions/latest/try", JSON.writeValueAsBytes(body))));
    }

    @Test
    void aCallGoesToTheMockAndShowsWhatWouldBeSent() throws Exception {
        JsonNode answer = tryTool(Map.of("tool", "orders_customer_list",
                "arguments", Map.of("instance", "mock", "query", Map.of("accountId", "a-1", "limit", 5))));
        assertThat(answer.at("/result/isError").asBoolean(false)).as(answer.toString()).isFalse();
        assertThat(answer.at("/requests/0/method").asText()).isEqualTo("GET");
        assertThat(answer.at("/requests/0/path").asText()).isEqualTo("/api/v2/customers");
        assertThat(answer.at("/requests/0/query").asText()).contains("accountId=a-1").contains("limit=5");
        assertThat(answer.at("/requests/0/headers").has("authorization")).isFalse();
        assertThat(answer.path("leftOut")).isEmpty();

        // the same server answers again (it stays warm), and validation errors come back as the agent sees them
        JsonNode invalid = tryTool(Map.of("tool", "orders_customer_list", "arguments", Map.of("instance", "mock")));
        assertThat(invalid.at("/result/isError").asBoolean()).isTrue();
        assertThat(invalid.at("/result/structuredContent/code").asText()).isEqualTo("INPUT_VALIDATION");
        assertThat(invalid.path("requests")).isEmpty();
    }

    @Test
    void aToolIsRequired() throws Exception {
        assertThat(send(bearer("POST", "/api/v1/servers/orders-mcp/versions/latest/try", JSON.writeValueAsBytes(Map.of()))).statusCode()).isEqualTo(422);
    }
}
