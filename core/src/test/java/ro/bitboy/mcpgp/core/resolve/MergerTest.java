package ro.bitboy.mcpgp.core.resolve;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.diag.Location;

import static org.assertj.core.api.Assertions.assertThat;

class MergerTest {

    private final Diagnostics diagnostics = new Diagnostics();
    private final Merger merger = new Merger(diagnostics, Location.of("variant.mcpgp.yaml"));

    private static JsonNode yaml(String text) throws Exception {
        return Documents.YAML.readTree(text);
    }

    @Test
    void mapsMergeDeeplyAndNullRemoves() throws Exception {
        JsonNode merged = merger.merge(yaml("{ a: { x: 1, y: 2 }, b: 1, c: 3 }"), yaml("{ a: { y: 20, z: 30 }, c: null }"));
        assertThat(merged.toString()).isEqualTo("{\"a\":{\"x\":1,\"y\":20,\"z\":30},\"b\":1}");
    }

    @Test
    void listsWithIdsMergeItemByItem() throws Exception {
        JsonNode merged = merger.merge(
                yaml("apis: [{ id: a, title: A, contracts: [{ version: '1' }, { version: '2' }] }, { id: b }]"),
                yaml("apis: [{ id: a, title: AA, contracts: [{ version: '1', $remove: true }, { version: '3' }] }, { id: c }]"));
        assertThat(merged.toString()).isEqualTo(
                "{\"apis\":[{\"id\":\"a\",\"title\":\"AA\",\"contracts\":[{\"version\":\"2\"},{\"version\":\"3\"}]},{\"id\":\"b\"},{\"id\":\"c\"}]}");
    }

    @Test
    void removeDeletesAnInheritedItem() throws Exception {
        JsonNode merged = merger.merge(yaml("apis: [{ id: a }, { id: b }]"), yaml("apis: [{ id: a, $remove: true }]"));
        assertThat(merged.toString()).isEqualTo("{\"apis\":[{\"id\":\"b\"}]}");
    }

    @Test
    void otherListsReplace() throws Exception {
        JsonNode merged = merger.merge(yaml("targets: [jar, docker]"), yaml("targets: [mcpb]"));
        assertThat(merged.toString()).isEqualTo("{\"targets\":[\"mcpb\"]}");
    }

    @Test
    void typeConflictsAreReportedAndTheLaterValueWins() throws Exception {
        JsonNode merged = merger.merge(yaml("x: { a: 1 }"), yaml("x: [1]"));
        assertThat(merged.toString()).isEqualTo("{\"x\":[1]}");
        assertThat(diagnostics.all()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo("MERGE_TYPE_CONFLICT");
            assertThat(d.location().pointer()).isEqualTo("/x");
        });
    }

    @Test
    void inputsAreNotModified() throws Exception {
        JsonNode base = yaml("{ a: 1 }");
        JsonNode overlay = yaml("{ a: 2 }");
        merger.merge(base, overlay);
        assertThat(base.toString()).isEqualTo("{\"a\":1}");
    }
}
