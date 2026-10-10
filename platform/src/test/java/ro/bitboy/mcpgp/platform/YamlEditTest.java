package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import ro.bitboy.mcpgp.platform.api.ApiException;
import ro.bitboy.mcpgp.platform.service.YamlEdit;
import ro.bitboy.mcpgp.platform.service.YamlEdit.Change;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Editing definitions as text (spec/platform-api.md §2.1): the change is made, everything else stays. */
class YamlEditTest {

    static final Path EXAMPLES = Path.of("..", "examples", "platform").toAbsolutePath().normalize();
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    static Change set(String path, String json) throws Exception {
        return new Change("set", path, json(json));
    }

    static JsonNode at(String yaml, String pointer) throws Exception {
        return YAML.readTree(yaml).at(pointer);
    }

    /** Lines of {@code before} that are not in {@code after}. */
    static List<String> lost(String before, String after) {
        List<String> kept = after.lines().toList();
        return before.lines().filter(l -> !kept.contains(l)).toList();
    }

    @Test
    void changesAScalarAndKeepsTheCommentsAroundIt() throws Exception {
        String orders = Files.readString(EXAMPLES.resolve("orders/orders.mcpgp.yaml"));
        String edited = YamlEdit.apply(orders, List.of(
                set("/exposure/mode", "\"plain\""),
                set("/apis/[id=orders]/select/access", "\"read-only\""),
                set("/safety/responseLimitKb", "512"),
                set("/instances/mode", "\"single\"")));
        assertThat(at(edited, "/exposure/mode").asText()).isEqualTo("plain");
        assertThat(at(edited, "/apis/0/select/access").asText()).isEqualTo("read-only");
        assertThat(at(edited, "/safety/responseLimitKb").asInt()).isEqualTo(512);
        assertThat(edited).contains("access: read-only                       # no DELETE tools")
                .contains("mode: single                                  # `instance` required on every tool")
                .startsWith("# Platform side: the build definition for orders-mcp.");
        assertThat(lost(orders, edited)).hasSize(4);
    }

    @Test
    void editsInsideFlowMappingsAndReplacesLists() throws Exception {
        String orders = Files.readString(EXAMPLES.resolve("orders/orders.mcpgp.yaml"));
        String edited = YamlEdit.apply(orders, List.of(
                set("/runtimeDefaults/transports/allowed", "[\"stdio\"]"),
                set("/build/targets", "[\"jar\"]"),
                set("/exposure/pinned", "[\"orders_customer_get\"]")));
        assertThat(edited).contains("transports: { allowed: [stdio], default: stdio }").contains("targets: [jar]")
                .contains("pinned: [orders_customer_get]");
        assertThat(lost(orders, edited)).hasSize(3);
    }

    @Test
    void addsMissingKeysWhereTheyBelong() throws Exception {
        String orders = Files.readString(EXAMPLES.resolve("orders/orders.mcpgp.yaml"));
        String edited = YamlEdit.apply(orders, List.of(
                set("/packs/search", "{}"),
                set("/safety/maxItems", "50"),
                set("/exposure/discoveryStyle", "\"search\"")));
        assertThat(at(edited, "/packs/search").isObject()).isTrue();
        assertThat(edited).contains("  jira: { $ref: packs/jira.yaml }\n  search: {}\n")
                .contains("  responseLimitKb: 256\n  maxItems: 50\n");
        assertThat(lost(orders, edited)).isEmpty();
    }

    @Test
    void removesKeysAndLeavesAnEmptiedMappingEmpty() throws Exception {
        String orders = Files.readString(EXAMPLES.resolve("orders/orders.mcpgp.yaml"));
        String edited = YamlEdit.apply(orders, List.of(new Change("remove", "/packs/jira", null), new Change("remove", "/packs/nothing", null)));
        assertThat(at(edited, "/packs").has("jira")).isFalse();
        assertThat(lost(orders, edited)).containsExactly("  jira: { $ref: packs/jira.yaml }");

        String single = "safety:\n  confirmDestructive: true   # keep\nname: x\n";
        assertThat(YamlEdit.apply(single, List.of(new Change("remove", "/safety/confirmDestructive", null)))).isEqualTo("safety:\n  {}   # keep\nname: x\n");
        assertThat(YamlEdit.apply("t: { a: 1, b: 2 }\n", List.of(new Change("remove", "/t/a", null)))).isEqualTo("t: { b: 2 }\n");
    }

    @Test
    void variantsGetTheirOverridesAddedAndNullStaysAValue() throws Exception {
        String readonly = Files.readString(EXAMPLES.resolve("orders-readonly/orders-readonly.mcpgp.yaml"));
        String edited = YamlEdit.apply(readonly, List.of(
                set("/packs/diagnostics", "null"),
                set("/safety/confirmDestructive", "false"),
                set("/apis/[id=orders]/downstreamAuth/supported", "[\"basic\"]"),
                set("/apis/[id=billing]/select/access", "\"read-only\"")));
        JsonNode data = YAML.readTree(edited);
        assertThat(data.at("/packs").has("diagnostics")).isTrue();
        assertThat(data.at("/packs/diagnostics").isNull()).isTrue();
        assertThat(data.at("/safety/confirmDestructive").asBoolean(true)).isFalse();
        assertThat(data.at("/apis/0/select/access").asText()).isEqualTo("read-only");
        assertThat(data.at("/apis/0/downstreamAuth/supported/0").asText()).isEqualTo("basic");
        assertThat(data.at("/apis/1/id").asText()).isEqualTo("billing");
        assertThat(edited).contains("extends: mcpgp://acme/servers/orders-mcp@latest   # CLI: --workspace ../workspace")
                .contains("  - { id: billing, select: { access: read-only } }");
    }

    @Test
    void quotesWhatWouldReadAsSomethingElse() throws Exception {
        String edited = YamlEdit.apply("a: x\n", List.of(set("/a", "\"2.0\""), set("/b", "\"yes\""), set("/c", "\"mcpgp://acme/x@1\""), set("/d", "\"a b\"")));
        JsonNode data = YAML.readTree(edited);
        assertThat(data.path("a").isTextual()).isTrue();
        assertThat(data.path("a").asText()).isEqualTo("2.0");
        assertThat(data.path("b").asText()).isEqualTo("yes");
        assertThat(data.path("c").asText()).isEqualTo("mcpgp://acme/x@1");
        assertThat(data.path("d").asText()).isEqualTo("a b");
    }

    @Test
    void refusesPathsThatDontFit() {
        assertThatThrownBy(() -> YamlEdit.apply("a: [1, 2]\n", List.of(set("/a/b", "1")))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> YamlEdit.apply("a: 1\n", List.of(set("exposure", "1")))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> YamlEdit.apply("a: 1\n", List.of(new Change("move", "/a", null)))).isInstanceOf(ApiException.class);
    }
}
