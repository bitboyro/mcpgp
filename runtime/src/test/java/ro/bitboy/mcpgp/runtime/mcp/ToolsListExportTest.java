package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.bitboy.mcpgp.core.manifest.ToolsList;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The manifest's {@code tools/list} export says what the runtime says, tool by tool. */
class ToolsListExportTest {

    static final Path EXAMPLES = Path.of("..", "examples", "manifest").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theExportIsWhatTheRuntimeSends() throws IOException {
        for (String file : new String[] {"orders-mcp.manifest.json", "ops-assistant.manifest.json"}) {
            JsonNode manifest = JSON.readTree(EXAMPLES.resolve(file).toFile());
            JsonNode exported = ToolsList.of(manifest).path("tools");
            if (manifest.at("/exposure/mode").asText("plain").equals("plain")) {
                assertThat(exported.size()).as(file).isEqualTo(manifest.path("tools").size());
            }
            assertThat(exported).as(file).isNotEmpty();
            for (JsonNode mine : exported) {
                JsonNode tool = null;
                for (JsonNode candidate : manifest.path("tools")) {
                    if (candidate.path("name").equals(mine.path("name"))) {
                        tool = candidate;
                    }
                }
                assertThat(tool).as(mine.path("name").asText()).isNotNull();
                JsonNode sent = JSON.valueToTree(ToolCatalog.toMcp(tool));
                assertThat(mine.path("name")).as(file).isEqualTo(sent.path("name"));
                assertThat(mine.path("title")).as(mine.path("name").asText()).isEqualTo(sent.path("title"));
                assertThat(mine.path("description")).as(mine.path("name").asText()).isEqualTo(sent.path("description"));
                assertThat(mine.path("inputSchema").toString().replace("\"x-mcpgp-instanceEnum\":true,", "").replace(",\"x-mcpgp-instanceEnum\":true", ""))
                        .as(mine.path("name").asText()).isEqualTo(sent.path("inputSchema").toString().replace("\"x-mcpgp-instanceEnum\":true,", "").replace(",\"x-mcpgp-instanceEnum\":true", ""));
                assertThat(mine.path("outputSchema")).as(mine.path("name").asText()).isEqualTo(sent.path("outputSchema"));
                for (String hint : new String[] {"readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint"}) {
                    assertThat(mine.at("/annotations/" + hint)).as(mine.path("name").asText() + " " + hint).isEqualTo(sent.at("/annotations/" + hint));
                }
            }
        }
    }

    @Test
    void discoveryModeListsOnlyWhatTheClientSees() throws IOException {
        JsonNode manifest = JSON.readTree(EXAMPLES.resolve("orders-mcp.manifest.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("exposure")).put("mode", "discovery");
        JsonNode exported = ToolsList.of(manifest).path("tools");
        assertThat(exported.findValuesAsText("name")).isNotEmpty().doesNotContain("orders_customer_list");
    }
}
