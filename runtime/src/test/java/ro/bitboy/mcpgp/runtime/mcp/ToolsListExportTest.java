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
    void retainedSchemasAndSessionHelperMatchRuntimeInEveryExposureMode() throws Exception {
        for (String mode : new String[] {"plain", "hybrid", "discovery", "toolsets"}) {
            for (String retention : new String[] {"inline", "auto", "reference"}) {
                var root = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(EXAMPLES.resolve("orders-mcp.manifest.json").toFile());
                root.withObject("exposure").put("mode", mode);
                root.withObject("policies").withObject("results").put("mode", retention);
                root.withObject("policies").withObject("runtimeDefaults").withObject("configuration").withObject("sessions").put("enabled", true);
                root.withObject("policies").withObject("runtimeDefaults").withObject("configuration").putArray("instances")
                        .addObject().put("id", "test").put("baseUrl", "https://example.test").put("contract", "2.0")
                        .putObject("auth").put("type", "basic").put("username", "test").put("password", "${env:TEST_PASSWORD}");
                for (JsonNode tool : root.path("tools")) {
                    if (tool.path("kind").asText().equals("api")) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) tool).putObject("outputSchema").put("type", "object")
                                .putObject("$defs").putObject("entry").put("type", "string");
                    }
                }
                var manifest = ro.bitboy.mcpgp.runtime.manifest.Manifest.of(root);
                var config = new ro.bitboy.mcpgp.runtime.config.ConfigLoader(new ro.bitboy.mcpgp.schemas.Schemas()).load(manifest,
                        new ro.bitboy.mcpgp.runtime.config.ConfigLoader.Options(java.util.Optional.empty(), EXAMPLES,
                                java.util.Map.of("TEST_PASSWORD", "test"), java.util.Map.of()));
                var catalog = new ToolCatalog(manifest, config);
                for (JsonNode exported : ToolsList.of(root).path("tools")) {
                    JsonNode actual = catalog.tool(exported.path("name").asText()).orElseThrow();
                    JsonNode sent = JSON.valueToTree(ToolCatalog.toMcp(actual));
                    assertThat(exported.path("outputSchema")).as(mode + "/" + retention).isEqualTo(sent.path("outputSchema"));
                    if (exported.path("name").asText().equals("session_policy")) {
                        assertThat(exported.path("inputSchema")).isEqualTo(sent.path("inputSchema"));
                    }
                }
                assertThat(ToolsList.of(root).path("tools").findValuesAsText("name")).contains("session_policy");
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
