package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared notices (spec/platform-api.md §2.3): a library item many servers include, edited and applied from the platform. */
class SharedNoticesApiTest extends PlatformTestBase {

    static JsonNode description(String server, String tool) throws Exception {
        for (JsonNode t : json(send(bearer("GET", "/api/v1/servers/" + server + "/versions/latest/manifest", null))).path("tools")) {
            if (t.path("name").asText().equals(tool)) {
                return t;
            }
        }
        throw new AssertionError(tool + " not in " + server);
    }

    @Test
    void aSharedSetReachesEveryServerAndMovesThemToItsNextVersion() throws Exception {
        // orders-mcp includes acme-policy@1 (examples/platform/orders)
        JsonNode item = json(send(bearer("GET", "/api/v1/library/notices/acme-policy", null)));
        assertThat(item.at("/set/notices")).hasSize(2);
        assertThat(item.path("usedBy").toString()).contains("\"server\":\"orders-mcp\"").contains("\"pinned\":\"1\"");
        JsonNode definition = json(send(bearer("GET", "/api/v1/servers/orders-mcp/versions/latest/definition", null)));
        assertThat(definition.at("/noticeSources/acme-data").asText()).isEqualTo("mcpgp://acme/notices/acme-policy@1");
        assertThat(description("orders-mcp", "orders_order_refund").path("description").asText()).contains("Changes are audited.");

        // a server without it: the orders project without the include
        String plain = Files.readString(EXAMPLES.resolve("orders/orders.mcpgp.yaml"))
                .replace("  include: mcpgp://acme/notices/acme-policy@1", "  notices: [ { id: own, text: \"Own notice.\" } ]")
                .replace("name: orders-mcp", "name: orders-plain");
        Map<String, Object> files = JSON.readValue(project(EXAMPLES.resolve("orders")), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> projectFiles = new java.util.LinkedHashMap<>((Map<String, String>) files.get("files"));
        projectFiles.put("orders.mcpgp.yaml", plain);
        assertThat(send(bearer("POST", "/api/v1/servers?id=orders-plain", JSON.writeValueAsBytes(Map.of("files", projectFiles)))).statusCode())
                .isEqualTo(201);

        // a new notice in the set, edited on the platform: its next version
        assertThat(send(bearer("POST", "/api/v1/library/notices/acme-policy/edits", JSON.writeValueAsBytes(Map.of("base", 1, "changes",
                List.of(Map.of("op", "set", "path", "/notices/[id=acme-ai]", "value", Map.of("text", "Answers may be wrong; check before acting."))))))
        ).statusCode()).isEqualTo(201);

        JsonNode results = json(send(bearer("POST", "/api/v1/library/notices/acme-policy/apply", JSON.writeValueAsBytes(Map.of()))));
        assertThat(results.path("results").toString())
                .contains("{\"server\":\"orders-mcp\",\"status\":\"updated\",\"version\":2,\"from\":\"mcpgp://acme/notices/acme-policy@1\"}")
                .contains("{\"server\":\"orders-plain\",\"status\":\"updated\",\"version\":2}");
        String refund = description("orders-plain", "orders_order_refund").path("description").asText();
        assertThat(refund).contains("Changes are audited.").contains("Answers may be wrong").contains("Own notice.");
        assertThat(description("orders-mcp", "orders_order_refund").path("description").asText()).contains("Answers may be wrong");
        assertThat(json(send(bearer("GET", "/api/v1/library/notices/acme-policy", null))).path("usedBy").toString())
                .contains("\"server\":\"orders-plain\",\"pinned\":\"2\"");

        // again: nothing to do
        assertThat(json(send(bearer("POST", "/api/v1/library/notices/acme-policy/apply", JSON.writeValueAsBytes(Map.of("servers", List.of("orders-plain"))))))
                .path("results").toString()).contains("\"status\":\"unchanged\"");
    }

    @Test
    void setsAreCheckedAndOnlyNoticesAreApplied() throws Exception {
        assertThat(send(bearer("POST", "/api/v1/library/notices?name=broken", JSON.writeValueAsBytes(Map.of("files",
                Map.of("broken.yaml", "notices:\n  - text: no id\n"))))).statusCode()).isEqualTo(422);
        assertThat(send(bearer("POST", "/api/v1/library/notices/acme-policy/edits", JSON.writeValueAsBytes(Map.of("base", 99, "changes",
                List.of(Map.of("op", "remove", "path", "/notices/[id=acme-data]")))))).statusCode()).isEqualTo(409);
        assertThat(send(bearer("POST", "/api/v1/library/naming/acme-standard/apply", JSON.writeValueAsBytes(Map.of()))).statusCode()).isEqualTo(422);
        assertThat(send(bearer("POST", "/api/v1/library/notices/acme-policy/apply", JSON.writeValueAsBytes(Map.of("servers", List.of("nope")))))
                .statusCode()).isEqualTo(404);
        assertThat(send(bearer("POST", "/api/v1/library/notices/acme-policy/apply", JSON.writeValueAsBytes(Map.of("version", 42)))).statusCode())
                .isEqualTo(422);
    }
}
