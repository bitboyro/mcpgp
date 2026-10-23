package ro.bitboy.mcpgp.schemas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** Rules from the specs that the schemas must enforce. */
class SchemaRulesTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private final Schemas schemas = new Schemas();

    private JsonNode yaml(String text) throws IOException {
        return YAML.readTree(text);
    }

    private static final String MINIMAL_DEFINITION = """
            apiVersion: mcpgp/v1
            kind: McpServer
            metadata: { name: orders-mcp }
            apis:
              - id: orders
                contracts: [{ version: "2.0", spec: contracts/orders.yaml }]
            """;

    @Test
    void organizationDefaultsReuseRuntimeSettings() throws IOException {
        assertThat(schemas.validate(SchemaKind.ORGANIZATION_POLICY, yaml("""
                version: 1
                defaults:
                  settings:
                    readOnly: true
                    timeoutSeconds: 15
                    rateLimits: { enabled: true, concurrent: 4, perMinute: 60 }
                """))).isEmpty();
        assertThat(schemas.validate(SchemaKind.ORGANIZATION_POLICY,
                yaml("version: 1\ndefaults: { settings: { timeoutSeconds: 0 } }"))).isNotEmpty();
    }

    @Test
    void minimalDefinitionIsValid() throws IOException {
        assertThat(schemas.validate(SchemaKind.DEFINITION, yaml(MINIMAL_DEFINITION))).isEmpty();
    }

    @Test
    void definitionWithoutApisIsInvalidUnlessItExtendsAnother() throws IOException {
        String noApis = """
                apiVersion: mcpgp/v1
                kind: McpServer
                metadata: { name: orders-mcp }
                """;
        assertThat(schemas.validate(SchemaKind.DEFINITION, yaml(noApis))).isNotEmpty();
        assertThat(schemas.validate(SchemaKind.DEFINITION, yaml(noApis + "extends: mcpgp://acme/servers/orders@latest\n")))
                .isEmpty();
    }

    @Test
    void definitionCannotHaveBothApiAndApis() throws IOException {
        String both = MINIMAL_DEFINITION + """
                api:
                  id: other
                  contracts: [{ version: "1.0", spec: x.yaml }]
                """;
        assertThat(schemas.validate(SchemaKind.DEFINITION, yaml(both))).isNotEmpty();
    }

    @Test
    void ruleMustChangeSomethingAndUseOneTextOperation() throws IOException {
        assertThat(schemas.validate(SchemaKind.RULES, yaml("rules: [{ match: { tag: x } }]"))).isNotEmpty();
        assertThat(schemas.validate(SchemaKind.RULES,
                yaml("rules: [{ match: { tag: x }, description: { prepend: a, append: b } }]"))).isNotEmpty();
        assertThat(schemas.validate(SchemaKind.RULES,
                yaml("rules: [{ match: { tag: x }, description: { prepend: '[X] ' } }]"))).isEmpty();
    }

    @Test
    void certificateMaterialIsExactlyOneOfPathOrBase64() throws IOException {
        String base = """
                instance:
                  baseUrl: orders.acme.internal
                  auth: { type: basic, username: u, password: "${env:P}" }
                  tls:
                    ca: %s
                """;
        assertThat(schemas.validate(SchemaKind.RUNTIME_CONFIG, yaml(base.formatted("{ path: ./ca.pem }")))).isEmpty();
        assertThat(schemas.validate(SchemaKind.RUNTIME_CONFIG, yaml(base.formatted("{ base64: QUJD }")))).isEmpty();
        assertThat(schemas.validate(SchemaKind.RUNTIME_CONFIG, yaml(base.formatted("{ path: ./ca.pem, base64: QUJD }"))))
                .isNotEmpty();
        assertThat(schemas.validate(SchemaKind.RUNTIME_CONFIG, yaml(base.formatted("{}")))).isNotEmpty();
    }

    @Test
    void basicAuthNeedsUsernameAndPassword() throws IOException {
        String config = """
                instance:
                  baseUrl: orders.acme.internal
                  auth: { type: basic, username: u }
                """;
        assertThat(schemas.validate(SchemaKind.RUNTIME_CONFIG, yaml(config))).isNotEmpty();
    }

    @Test
    void runtimeConfigUsesOnlyOneWayToDeclareInstances() throws IOException {
        String config = """
                instance: { baseUrl: a.example }
                instances: [{ id: b, baseUrl: b.example }]
                """;
        assertThat(schemas.validate(SchemaKind.RUNTIME_CONFIG, yaml(config))).isNotEmpty();
    }

    @Test
    void auditErrorEventsNeedACode() throws IOException {
        String event = """
                { "ts": "2026-10-09T10:42:18.311Z", "v": 1, "type": "tool.call", "server": "orders-mcp",
                  "tool": "orders_customer_get", "latencyMs": 12, "outcome": "error", "cid": "c-1" }
                """;
        assertThat(schemas.validate(SchemaKind.AUDIT_EVENT, yaml(event))).isNotEmpty();
    }
}
