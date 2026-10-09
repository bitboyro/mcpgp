package ro.bitboy.mcpgp.core.resolve;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition.ContractKey;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The orders example and its read-only variant resolve cleanly, with the expected merge results. */
class ExamplesResolveTest {

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    static final Path PLATFORM = ROOT.resolve("examples/platform");

    private final Schemas schemas = new Schemas();
    private final DefinitionResolver resolver = new DefinitionResolver(schemas);

    private Workspace workspace() {
        Diagnostics diagnostics = new Diagnostics();
        Workspace workspace = Workspace.load(PLATFORM.resolve("workspace/mcpgp-workspace.yaml"), schemas, diagnostics);
        assertThat(diagnostics.all()).isEmpty();
        return workspace;
    }

    @Test
    void ordersResolves() {
        var result = resolver.resolve(PLATFORM.resolve("orders/orders.mcpgp.yaml"), workspace());

        assertThat(result.diagnostics()).filteredOn(d -> d.level() == Diagnostic.Level.ERROR).isEmpty();
        ResolvedDefinition d = result.definition().orElseThrow();

        assertThat(d.rules()).hasSize(7);
        assertThat(d.rules().get(0).location().file()).isEqualTo("overrides/00-global.yaml");
        assertThat(d.rules().get(6).location().file()).isEqualTo("overrides/20-orders.yaml");

        assertThat(d.contracts()).containsOnlyKeys(new ContractKey("orders", "1.0"), new ContractKey("orders", "2.0"));
        var v2 = d.contracts().get(new ContractKey("orders", "2.0"));
        assertThat(v2.spec()).isRegularFile().hasFileName("orders-2.0.yaml");
        assertThat(v2.overlays()).singleElement().satisfies(o -> assertThat(o).hasFileName("2.0-descriptions.yaml"));

        JsonNode naming = d.document().get("naming");
        assertThat(naming.has("extends")).isFalse();
        assertThat(naming.path("vocabulary").path("retrieve|fetch|read").asText()).isEqualTo("get");   // from the policy
        assertThat(naming.path("vocabulary").path("customer_account").asText()).isEqualTo("customer"); // own
        assertThat(naming.path("shape").path("order").asText()).isEqualTo("object_verb");
        assertThat(d.document().has("overrides")).isFalse();
        assertThat(d.parent()).isNull();
        assertThat(d.files()).allSatisfy(f -> assertThat(Files.isRegularFile(f)).isTrue());
    }

    @Test
    void variantInheritsAndOverridesOnlyWhatItSays() {
        var result = resolver.resolve(PLATFORM.resolve("orders-readonly/orders-readonly.mcpgp.yaml"), workspace());

        assertThat(result.diagnostics()).filteredOn(x -> x.level() == Diagnostic.Level.ERROR).isEmpty();
        ResolvedDefinition d = result.definition().orElseThrow();
        JsonNode doc = d.document();

        assertThat(d.parent()).isEqualTo("orders-mcp");
        assertThat(doc.at("/metadata/name").asText()).isEqualTo("orders-readonly");
        JsonNode api = doc.at("/apis/0");
        assertThat(api.at("/select/access").asText()).isEqualTo("read-only");             // overridden
        assertThat(api.at("/select/exclude").isArray()).isTrue();                          // inherited
        assertThat(api.at("/downstreamAuth/supported").size()).isEqualTo(4);               // inherited
        assertThat(d.contracts()).hasSize(2);                                               // inherited, not wiped
        assertThat(doc.at("/instances/mode").asText()).isEqualTo("single");
        assertThat(doc.path("packs").has("jira")).isFalse();                               // removed with null
        assertThat(doc.path("packs").has("recording")).isFalse();
        assertThat(doc.path("packs").has("audit")).isTrue();
        assertThat(doc.at("/build/targets").toString()).isEqualTo("[\"mcpb\",\"jar\"]");  // list replaced
        assertThat(d.rules()).hasSize(7);                                                   // parent's rules
    }

    @Test
    void variantWithoutWorkspaceReportsTheUnresolvedReference() {
        var result = resolver.resolve(PLATFORM.resolve("orders-readonly/orders-readonly.mcpgp.yaml"), Workspace.none());
        assertThat(result.definition()).isEmpty();
        assertThat(result.diagnostics()).anySatisfy(diag -> {
            assertThat(diag.code()).isEqualTo("WORKSPACE_REFERENCE_UNRESOLVED");
            assertThat(diag.location().pointer()).isEqualTo("/extends");
        });
    }
}
