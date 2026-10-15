package ro.bitboy.mcpgp.core.diff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;

/** What changed between two builds (cli.md §3.5). */
class ManifestDiffTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path ORDERS = Path.of("..", "examples", "manifest", "orders-mcp.manifest.json").toAbsolutePath().normalize();

    static ObjectNode orders() throws Exception {
        return (ObjectNode) JSON.readTree(ORDERS.toFile());
    }

    static ObjectNode tool(ObjectNode manifest, String name) {
        for (JsonNode t : manifest.path("tools")) {
            if (t.path("name").asText().equals(name)) {
                return (ObjectNode) t;
            }
        }
        throw new AssertionError(name);
    }

    @Test
    void theSameBuildHasNoChanges() throws Exception {
        ManifestDiff.Result same = ManifestDiff.compare(orders(), orders());
        assertThat(same.empty()).isTrue();
        assertThat(DiffReport.summary(same)).startsWith("no changes");
    }

    @Test
    void renamesRemovalsAndInputChangesAreBreakingAdditionsAreNot() throws Exception {
        ObjectNode before = orders();
        ObjectNode after = orders();
        tool(after, "orders_customer_get").put("name", "orders_customer_fetch");               // renamed, same operation
        ArrayNode tools = (ArrayNode) after.path("tools");
        for (Iterator<JsonNode> it = tools.iterator(); it.hasNext(); ) {
            if (it.next().path("name").asText().equals("orders_order_refund")) {
                it.remove();                                                                 // removed
            }
        }
        ObjectNode list = tool(after, "orders_customer_list");
        ObjectNode query = (ObjectNode) list.at("/inputSchema/properties/query");
        ((ObjectNode) query.path("properties")).putObject("region").put("type", "string");     // a new required input
        query.withArray("required").add("region");
        tool(after, "orders_customer_list").put("description", "Lists customers, now by region.");
        ((ObjectNode) after.path("exposure")).put("mode", "plain");

        ManifestDiff.Result diff = ManifestDiff.compare(before, after);
        assertThat(diff.changes()).extracting(ManifestDiff.Change::kind)
                .contains("tool.renamed", "tool.removed", "input.added", "tool.description", "server.exposure");
        assertThat(diff.changes()).filteredOn(c -> c.kind().equals("tool.renamed")).singleElement()
                .satisfies(c -> assertThat(c.text()).contains("orders_customer_get"));
        assertThat(diff.changes()).filteredOn(c -> c.kind().equals("input.added")).singleElement()
                .satisfies(c -> {
                    assertThat(c.impact()).isEqualTo(ManifestDiff.Impact.BREAKING);
                    assertThat(c.text()).isEqualTo("input query.region added (required)");
                });
        assertThat(diff.breaking()).isEqualTo(3);
        // breaking first
        assertThat(diff.changes().get(0).impact()).isEqualTo(ManifestDiff.Impact.BREAKING);

        String md = DiffReport.markdown("orders-mcp", diff);
        assertThat(md).startsWith("### orders-mcp: ").contains("3 breaking").contains("- `orders_customer_fetch`: renamed from orders_customer_get");
    }
}
