package ro.bitboy.mcpgp.core.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.resolve.Workspace;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A server combining three APIs (requirements: "server can combine several APIs"): tools prefixed per
 * API, an instances_list per API, unprefixed pack tools (tools-contract §3), and each API's own
 * selection and sign-in types.
 */
class OpsAssistantManifestTest {

    static final Path PLATFORM = Path.of("..", "examples", "platform").toAbsolutePath().normalize();
    static final Path EXAMPLE = Path.of("..", "examples", "manifest", "ops-assistant.manifest.json");
    static final ManifestWriter.Build BUILD = new ManifestWriter.Build(Instant.parse("2026-10-10T12:00:00Z"), "0.1.0", null, null, null);
    static ObjectNode manifest;

    @BeforeAll
    static void compile() throws IOException {
        Schemas schemas = new Schemas();
        Workspace workspace = Workspace.load(PLATFORM.resolve("workspace/mcpgp-workspace.yaml"), schemas, new Diagnostics());
        Compiler.Result result = new Compiler(schemas).compile(PLATFORM.resolve("ops-assistant/ops-assistant.mcpgp.yaml"),
                new Compiler.Options(workspace, false, BUILD));
        assertThat(result.diagnostics()).filteredOn(d -> d.level() == Diagnostic.Level.ERROR).isEmpty();
        manifest = result.manifest().orElseThrow();
        if (Boolean.getBoolean("mcpgp.updateExpected")) {
            Files.writeString(EXAMPLE, CanonicalJson.pretty(manifest));
        }
    }

    static List<String> names(String kind) {
        List<String> names = new ArrayList<>();
        manifest.path("tools").forEach(t -> {
            if (kind == null || t.path("kind").asText().equals(kind)) {
                names.add(t.path("name").asText());
            }
        });
        return names;
    }

    @Test
    void matchesTheExampleFile() throws IOException {
        assertThat(CanonicalJson.pretty(manifest))
                .as("examples/manifest/ops-assistant.manifest.json (run with -Dmcpgp.updateExpected=true to regenerate)")
                .isEqualTo(Files.readString(EXAMPLE));
    }

    @Test
    void toolsArePrefixedPerApi() {
        assertThat(manifest.path("apis").findValuesAsText("id")).containsExactly("orders", "tickets", "monitoring");
        assertThat(names("api")).allSatisfy(n -> assertThat(n).matches("(orders|tickets|monitoring)_.+"));
        assertThat(names("api")).contains("orders_order_search", "tickets_ticket_create", "tickets_ticket_comment_create", "monitoring_alert_list",
                "monitoring_metric_search");
    }

    @Test
    void eachApiKeepsItsOwnSelection() {
        // monitoring is read-only; tickets is read-write (no DELETE) and only the included tags
        assertThat(names("api")).filteredOn(n -> n.startsWith("monitoring_")).noneMatch(n -> n.contains("acknowledge"));
        assertThat(names("api")).doesNotContain("tickets_ticket_delete", "tickets_user_list");
        assertThat(manifest.path("tools")).filteredOn(t -> t.path("api").asText().equals("monitoring"))
                .allSatisfy(t -> assertThat(t.at("/annotations/readOnlyHint").asBoolean()).isTrue());
    }

    @Test
    void instancesAreListedPerApiAndPackToolsHaveNoPrefix() {
        assertThat(names("builtin")).contains("orders_instances_list", "tickets_instances_list", "monitoring_instances_list")
                .doesNotContain("instances_list");
        assertThat(names("pack")).contains("audit_list");
        assertThat(names("pack")).noneMatch(n -> n.startsWith("orders_"));
    }

    @Test
    void signInTypesAreDeclaredPerApi() {
        JsonNode apis = manifest.path("apis");
        assertThat(apis.get(1).at("/upstreamAuth/supported")).extracting(JsonNode::asText).containsExactly("basic", "bearer");
        assertThat(apis.get(2).at("/upstreamAuth/supported")).extracting(JsonNode::asText).containsExactly("oauth2ClientCredentials");
    }
}
