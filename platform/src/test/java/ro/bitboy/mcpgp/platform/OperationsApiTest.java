package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Every operation and what became of it (spec/platform-api.md §2.1), and turning one off and on again. */
class OperationsApiTest extends PlatformTestBase {

    static JsonNode operations() throws Exception {
        return json(send(bearer("GET", "/api/v1/servers/orders-mcp/versions/latest/operations", null)));
    }

    static JsonNode operation(JsonNode list, String operationId) {
        for (JsonNode o : list.at("/apis/0/operations")) {
            if (o.path("operationId").asText().equals(operationId)) {
                return o;
            }
        }
        throw new AssertionError("no operation " + operationId);
    }

    @Test
    void everyOperationSaysWhatBecameOfIt() throws Exception {
        JsonNode list = operations();
        assertThat(list.at("/apis/0/id").asText()).isEqualTo("orders");
        assertThat(list.at("/apis/0/select/exclude/1/path").asText()).isEqualTo("/internal/**");

        JsonNode customers = operation(list, "listCustomers");
        assertThat(customers.path("state").asText()).isEqualTo("tool");
        assertThat(customers.path("tool").asText()).isEqualTo("orders_customer_list");
        assertThat(customers.path("method").asText()).isEqualTo("GET");

        JsonNode delete = operation(list, "deleteCustomer");
        assertThat(delete.path("state").asText()).isEqualTo("excluded");
        assertThat(delete.at("/exclusion/kind").asText()).isEqualTo("access");

        JsonNode users = operation(list, "listUsers");
        assertThat(users.at("/exclusion/kind").asText()).isEqualTo("exclude");
        assertThat(users.at("/exclusion/index").asInt()).isZero();
        assertThat(users.at("/exclusion/selector/tag/0").asText()).isEqualTo("users-roles");

        // tagged system and under /internal: the first matching entry is the one reported
        JsonNode metrics = operation(list, "getInternalMetrics");
        assertThat(metrics.at("/exclusion/index").asInt()).isZero();
    }

    @Test
    void aDryRunListsTheOperationsOfTheEditedDefinition() throws Exception {
        JsonNode before = operations();
        var preview = json(send(bearer("POST", "/api/v1/servers/orders-mcp/edits", JSON.writeValueAsBytes(Map.of("base", before.path("version").asInt(),
                "dryRun", true, "operations", true, "changes", List.of(Map.of("op", "set", "path", "/apis/[id=orders]/select/access", "value", "all")))))));
        assertThat(operation(preview.path("operations"), "deleteCustomer").path("state").asText()).isEqualTo("tool");
        assertThat(operation(operations(), "deleteCustomer").path("state").asText()).isEqualTo("excluded");
    }

    @Test
    void anExcludeEntryTurnsAToolOffAndRemovingItTurnsItOn() throws Exception {
        JsonNode before = operations();
        int base = before.path("version").asInt();
        List<Object> exclude = new java.util.ArrayList<>(JSON.convertValue(before.at("/apis/0/select/exclude"), List.class));
        exclude.add(Map.of("operationId", "listAccounts"));
        var off = send(bearer("POST", "/api/v1/servers/orders-mcp/edits", JSON.writeValueAsBytes(Map.of("base", base, "message", "Tools & rules",
                "changes", List.of(Map.of("op", "set", "path", "/apis/[id=orders]/select/exclude", "value", exclude))))));
        assertThat(off.statusCode()).as(new String(off.body())).isEqualTo(201);

        JsonNode after = operations();
        assertThat(after.path("version").asInt()).isEqualTo(base + 1);
        JsonNode accounts = operation(after, "listAccounts");
        assertThat(accounts.path("state").asText()).isEqualTo("excluded");
        assertThat(accounts.at("/exclusion/index").asInt()).isEqualTo(2);
        assertThat(accounts.at("/exclusion/selector/operationId").asText()).isEqualTo("listAccounts");

        exclude.remove(2);
        var on = send(bearer("POST", "/api/v1/servers/orders-mcp/edits", JSON.writeValueAsBytes(Map.of("base", base + 1,
                "changes", List.of(Map.of("op", "set", "path", "/apis/[id=orders]/select/exclude", "value", exclude))))));
        assertThat(on.statusCode()).isEqualTo(201);
        assertThat(operation(operations(), "listAccounts").path("state").asText()).isEqualTo("tool");
    }
}
