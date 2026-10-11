package ro.bitboy.mcpgp.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** `mcpgp init --from-spec` and `mcpgp test`: the packaged runtime against a mock made from the spec. */
class TestCommandTest {

    static final Path EXAMPLES = Path.of("..", "examples").toAbsolutePath().normalize();
    static final Path ORDERS_SPEC = EXAMPLES.resolve("platform/orders/contracts/orders-2.0.yaml");

    final StringWriter out = new StringWriter();
    final StringWriter err = new StringWriter();

    int run(String... args) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        return Main.run(new PrintWriter(out, true), new PrintWriter(err, true), args);
    }

    @Test
    void theExampleSuitePassesAgainstTheMock() {
        int code = run("test", "--workspace", EXAMPLES.resolve("platform/workspace").toString(),
                "--suite", EXAMPLES.resolve("pipeline/servers/orders/tests").toString(),
                EXAMPLES.resolve("platform/orders/orders.mcpgp.yaml").toString());
        assertThat(code).as(out + "\n" + err).isZero();
        assertThat(out.toString()).contains("✓ smoke: the server starts and lists its tools")
                .contains("✓ smoke.yaml: refund is a write tool and returns the order")      // the spec's response example
                .contains("4 passed, 0 failed");
    }

    @Test
    void initScaffoldsAProjectThatTestsGreenAndFailuresSayWhy(@TempDir Path dir) throws Exception {
        assertThat(run("init", "--from-spec", ORDERS_SPEC.toString(), "--out", dir.toString())).as(err.toString()).isZero();
        Path definition = dir.resolve("example-orders-mcp.mcpgp.yaml");
        assertThat(Files.readString(definition)).contains("name: example-orders-mcp").contains("- id: example_orders")
                .contains("{ version: \"2.0.3\", spec: contracts/orders-2.0.yaml }").contains("mode: single");
        assertThat(dir.resolve("contracts/orders-2.0.yaml")).hasSameBinaryContentAs(ORDERS_SPEC);
        assertThat(Files.readString(dir.resolve("tests/smoke.yaml"))).contains("tool: example_orders_account_list");
        assertThat(run("init", "--from-spec", ORDERS_SPEC.toString(), "--out", dir.toString())).isEqualTo(1);
        assertThat(err.toString()).contains("not overwriting");

        Files.writeString(dir.resolve("tests/more.yaml"), """
                cases:
                  - name: a required argument is missing
                    tool: example_orders_customer_list
                    args: {}
                    expect: { ok: true }
                  - name: the mock answers with the schema
                    tool: example_orders_order_refund
                    args: { path: { id: "o-1" }, body: { amount: 5 } }
                    expect: { ok: true, structured: { status: open } }
                  - name: an expected error code
                    tool: example_orders_customer_list
                    args: {}
                    expect: { code: INPUT_VALIDATION }
                """);
        assertThat(run("test", "--json", definition.toString())).isEqualTo(4);
        JsonNode results = new ObjectMapper().readTree(out.toString());
        assertThat(results).hasSize(1 + 3 + 3);
        assertThat(results.get(1).path("name").asText()).isEqualTo("a required argument is missing");
        assertThat(results.get(1).path("problem").asText()).startsWith("expected success, got INPUT_VALIDATION");
        assertThat(results.get(2).path("problem").asText()).isEqualTo("structured content: /status is \"refunded\", expected \"open\"");
        assertThat(results.get(3).path("passed").asBoolean()).isTrue();

        Files.writeString(dir.resolve("tests/more.yaml"), "cases: [ { name: typo, tool: example_orders_custmer_list, expect: { ok: true } } ]\n");
        assertThat(run("test", definition.toString())).isEqualTo(4);
        assertThat(out.toString()).contains("✗ more.yaml: typo: no tool named example_orders_custmer_list");
    }

    @Test
    void initRefusesWhatIsNotASpec(@TempDir Path dir) throws Exception {
        Path notes = Files.writeString(dir.resolve("notes.yaml"), "hello: world\n");
        assertThat(run("init", "--from-spec", notes.toString(), "--out", dir.resolve("p").toString())).isEqualTo(1);
        assertThat(err.toString()).contains("not an OpenAPI or Swagger document");
        assertThat(run("init", "--from-spec", "http://specs.example.com/openapi.yaml")).isEqualTo(1);
        assertThat(err.toString()).contains("use https");
    }

    @Test
    void mockValuesFollowTheSchema() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode schema = json.readTree("""
                { "type": "object", "properties": {
                    "id": { "type": "string", "format": "uuid" }, "n": { "type": "integer", "minimum": 3 },
                    "tags": { "type": "array", "items": { "enum": ["a", "b"] } }, "note": { "type": ["null", "string"], "example": "hi" },
                    "both": { "allOf": [ { "properties": { "x": { "const": 1 } } }, { "properties": { "y": { "default": true } } } ] } } }
                """);
        assertThat(ro.bitboy.mcpgp.packager.trial.SpecMock.value(schema, 0).toString()).isEqualTo(json.readTree("""
                { "id": "00000000-0000-4000-8000-000000000000", "n": 3, "tags": ["a"], "note": "hi", "both": { "x": 1, "y": true } }
                """).toString());
        // a $ref to a shared schema
        JsonNode order = json.readTree("{ \"type\": \"object\", \"properties\": { \"status\": { \"enum\": [\"open\", \"paid\"] } } }");
        assertThat(ro.bitboy.mcpgp.packager.trial.SpecMock.value(json.readTree("{ \"$ref\": \"#/components/schemas/Order\" }"),
                java.util.Map.of("Order", order), 0).toString()).isEqualTo("{\"status\":\"open\"}");
    }
}
