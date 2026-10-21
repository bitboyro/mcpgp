package ro.bitboy.mcpgp.core.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.resolve.Workspace;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The orders example's manifest: shape, schemas, variants and determinism. */
class OrdersManifestTest {

    static final Path PLATFORM = Path.of("..", "examples", "platform").toAbsolutePath().normalize();
    static final ManifestWriter.Build BUILD = new ManifestWriter.Build(
            Instant.parse("2026-10-09T10:40:00Z"), "0.1.0", null, 3,
            "git+https://git.example.com/integration/mcp-defs.git#9c41e7a");
    /** The example in the docs is this test's output, so it can't drift from what the compiler does. */
    static final Path EXAMPLE = Path.of("..", "examples", "manifest", "orders-mcp.manifest.json");
    static Compiler compiler;
    static Workspace workspace;
    static Compiler.Result orders;
    static ObjectNode manifest;

    @BeforeAll
    static void compile() throws IOException {
        Schemas schemas = new Schemas();
        workspace = Workspace.load(PLATFORM.resolve("workspace/mcpgp-workspace.yaml"), schemas, new Diagnostics());
        compiler = new Compiler(schemas);
        orders = compiler.compile(PLATFORM.resolve("orders/orders.mcpgp.yaml"), new Compiler.Options(workspace, false, BUILD));
        assertThat(orders.diagnostics()).filteredOn(d -> d.level() == ro.bitboy.mcpgp.core.diag.Diagnostic.Level.ERROR).isEmpty();
        manifest = orders.manifest().orElseThrow();
        if (Boolean.getBoolean("mcpgp.updateExpected")) {
            Files.writeString(EXAMPLE, CanonicalJson.pretty(manifest));
        }
    }

    @Test
    void matchesTheDocumentedExample() throws IOException {
        assertThat(CanonicalJson.pretty(manifest))
                .as("examples/manifest/orders-mcp.manifest.json (run with -Dmcpgp.updateExpected=true to regenerate)")
                .isEqualTo(Files.readString(EXAMPLE));
    }

    private static JsonNode tool(String name) {
        for (JsonNode tool : manifest.path("tools")) {
            if (tool.path("name").asText().equals(name)) {
                return tool;
            }
        }
        throw new AssertionError(name + " not in manifest");
    }

    @Test
    void serverFollowsTheDefaultContractVersion() {
        assertThat(manifest.path("server").path("version").asText()).isEqualTo("2.0.0+3");
        assertThat(manifest.path("server").path("instructions").asText()).endsWith("call instances_list to see the configured servers.");
        assertThat(manifest.path("build").path("createdAt").asText()).isEqualTo("2026-10-09T10:40:00Z");
    }

    @Test
    void isDeterministic() {
        Compiler.Result again = compiler.compile(PLATFORM.resolve("orders/orders.mcpgp.yaml"), new Compiler.Options(workspace, false, BUILD));
        assertThat(CanonicalJson.compact(again.manifest().orElseThrow())).isEqualTo(CanonicalJson.compact(manifest));
    }

    @Test
    void containsApiBuiltinAndPackToolsSortedByName() {
        assertThat(manifest.path("tools")).extracting(t -> t.path("name").asText() + ":" + t.path("kind").asText()).containsExactly(
                "call_tool:builtin", "describe_tool:builtin", "instances_list:builtin",
                "orders_account_list:api", "orders_audit_list:pack", "orders_connectivity_check:pack",
                "orders_customer_contact_list:api", "orders_customer_create:api", "orders_customer_create_multipart:api",
                "orders_customer_get:api", "orders_customer_list:api", "orders_customer_update:api",
                "orders_explain_tool:pack", "orders_invoice_document_get:api", "orders_manifest_info:pack",
                "orders_order_refund:api", "orders_order_search:api", "orders_overdue_invoice_list:api",
                "orders_recording_list:pack", "orders_recording_sessions:pack", "orders_recording_start:pack",
                "orders_recording_stop:pack", "orders_tls_certificate_fetch:pack", "search_tools:builtin");
        assertThat(tool("instances_list").path("inputSchema").path("properties").size()).isZero();
        assertThat(tool("search_tools").path("inputSchema").path("required")).extracting(JsonNode::asText)
                .containsExactly("instance", "query");
    }

    @Test
    void groupsInputsByLocation() {
        JsonNode input = tool("orders_customer_get").path("inputSchema");
        assertThat(input.path("required")).extracting(JsonNode::asText).containsExactly("instance", "path");
        assertThat(input.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(input.at("/properties/instance/x-mcpgp-instanceEnum").asBoolean()).isTrue();
        assertThat(input.at("/properties/path/required/0").asText()).isEqualTo("id");
        assertThat(input.at("/properties/path/additionalProperties").asBoolean(true)).isFalse();
        // only in contract 2.0
        JsonNode expand = input.at("/properties/query/properties/expand");
        assertThat(expand.path("x-mcpgp-contracts")).extracting(JsonNode::asText).containsExactly("2.0");
        assertThat(expand.path("description").asText()).endsWith("(2.0 only)");
        assertThat(input.at("/properties/query/required").isMissingNode()).isTrue();
    }

    @Test
    void removesReadOnlyPropertiesFromInputs() {
        JsonNode body = tool("orders_customer_create").at("/inputSchema/properties/body");
        assertThat(body.path("properties").has("id")).isFalse();
        assertThat(body.path("properties").has("createdAt")).isFalse();
        assertThat(body.path("required")).extracting(JsonNode::asText).containsExactly("name", "accountId");
    }

    @Test
    void multipartPartsUseFileObjectsAndEncodingContentTypes() {
        JsonNode tool = tool("orders_customer_create_multipart");
        JsonNode document = tool.at("/inputSchema/properties/body/properties/document");
        assertThat(document.path("oneOf")).hasSize(2);
        assertThat(document.at("/oneOf/0/properties/contentType/enum/0").asText()).isEqualTo("application/pdf");
        assertThat(document.at("/oneOf/1/required/0").asText()).isEqualTo("path");
        JsonNode body = tool.at("/variants/2.0/body");
        assertThat(body.path("contentType").asText()).isEqualTo("multipart/form-data");
        assertThat(body.path("parts")).extracting(p -> p.path("name").asText() + "=" + p.path("contentTypes").get(0).asText()
                + (p.path("file").asBoolean() ? " file" : "")).containsExactly("payload=application/json", "document=application/pdf file");
        assertThat(tool.path("variants").has("1.0")).isFalse();
    }

    @Test
    void acceptListsEveryResponseTypeInSpecOrder() {
        assertThat(tool("orders_invoice_document_get").at("/variants/2.0/accept")).extracting(JsonNode::asText)
                .containsExactly("application/pdf", "application/problem+json");
    }

    @Test
    void variantsReferenceSharedSchemas() {
        JsonNode responses = tool("orders_customer_get").at("/variants/1.0/responses");
        assertThat(responses.at("/0/schema/$ref").asText()).isEqualTo("#/schemas/orders@1.0/Customer");
        assertThat(manifest.at("/schemas/orders@1.0/Customer/type").asText()).isEqualTo("object");
        assertThat(manifest.at("/schemas/orders@2.0").has("CustomerInput")).isTrue();
    }

    @Test
    void recordsProvenanceAndDiagnostics() {
        JsonNode description = tool("orders_customer_get").at("/provenance/description/0");
        assertThat(description.path("file").asText()).isEqualTo("contracts/orders-2.0.yaml");
        assertThat(manifest.path("diagnostics")).filteredOn(d -> d.path("code").asText().equals("OPERATION_EXCLUDED"))
                .isEmpty();
        assertThat(orders.diagnostics()).anyMatch(d -> d.code().equals("OPERATION_EXCLUDED"));
        assertThat(manifest.path("diagnostics")).filteredOn(d -> d.path("level").asText().equals("warning"))
                .extracting(d -> d.path("code").asText()).containsExactly("PACK_UNKNOWN", "RULE_MATCHES_NOTHING");
    }

    @Test
    void exclusionDiagnosticsCanBeIncludedWithoutChangingToolsOrOtherDiagnostics() {
        Compiler.Result detailed = compiler.compile(PLATFORM.resolve("orders/orders.mcpgp.yaml"),
                new Compiler.Options(workspace, false, BUILD, null, true));
        ObjectNode full = detailed.manifest().orElseThrow();
        assertThat(full.path("diagnostics")).filteredOn(d -> d.path("code").asText().equals("OPERATION_EXCLUDED"))
                .extracting(d -> d.path("operation").asText()).contains("orders:DELETE:/customers/{id}");
        assertThat(full.path("tools")).isEqualTo(manifest.path("tools"));
        assertThat(detailed.diagnostics()).isEqualTo(orders.diagnostics());
        assertThat(full.path("diagnostics")).filteredOn(d -> !d.path("code").asText().equals("OPERATION_EXCLUDED"))
                .containsExactlyElementsOf(manifest.path("diagnostics"));
    }

    @Test
    void readOnlyVariantHasNoWriteTools() {
        Compiler.Result readonly = compiler.compile(PLATFORM.resolve("orders-readonly/orders-readonly.mcpgp.yaml"),
                new Compiler.Options(workspace, false, BUILD));
        JsonNode tools = readonly.manifest().orElseThrow().path("tools");
        assertThat(tools).filteredOn(t -> t.path("kind").asText().equals("api"))
                .allSatisfy(t -> assertThat(t.at("/annotations/readOnlyHint").asBoolean()).isTrue());
        assertThat(readonly.manifest().orElseThrow().at("/build/extends/name").asText()).isEqualTo("orders-mcp");
    }
}
