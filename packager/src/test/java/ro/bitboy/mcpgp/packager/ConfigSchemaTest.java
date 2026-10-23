package ro.bitboy.mcpgp.packager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigSchemaTest {
    @Test void generatedSchemasAcceptPoliciesAndRejectMalformedLists() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        var manifest = json.readTree(Path.of("../examples/manifest/ops-assistant.manifest.json").toFile());
        var generated = ConfigSchema.generate(manifest);
        var schema = registry.getSchema(generated);
        var document = json.readTree("""
                {"tools":{"allow":[]},"apis":{
                  "orders":{"tools":{"deny":["orders_customer_create"]},"instances":[{"id":"prod","baseUrl":"https://orders.example.com","tools":{"allow":["orders_*"]}}]},
                  "tickets":{"instances":[{"id":"prod","baseUrl":"https://tickets.example.com"}]},
                  "monitoring":{"instances":[{"id":"central","baseUrl":"https://monitor.example.com"}]}
                }}
                """);
        assertThat(schema.validate(document)).isEmpty();
        ((com.fasterxml.jackson.databind.node.ObjectNode) document.path("tools")).put("allow", "*");
        assertThat(schema.validate(document)).isNotEmpty();
        var single = ConfigSchema.generate(json.readTree(Path.of("../examples/manifest/orders-mcp.manifest.json").toFile()));
        assertThat(single.at("/properties/tools/$ref").asText()).isEqualTo("#/$defs/toolPolicy");
        assertThat(single.at("/$defs/instance_orders/properties/tools/$ref").asText()).isEqualTo("#/$defs/toolPolicy");
    }
}
