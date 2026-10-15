package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks results against their tool's outputSchema for the log, and never rejects one: the schema
 * comes from the API's spec, which a target's real answers often differ from, and an answer the
 * target really gave is better for the agent than an error (tools-contract §6). A mismatch is logged
 * once per schema, so the builder can fix the spec.
 */
final class LenientOutputValidator implements JsonSchemaValidator {

    private static final Logger LOG = LoggerFactory.getLogger(LenientOutputValidator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final Map<Map<String, Object>, Schema> schemas = new ConcurrentHashMap<>();
    private final Map<Map<String, Object>, Boolean> reported = new ConcurrentHashMap<>();

    @Override
    public ValidationResponse validate(Map<String, Object> schema, Object structuredContent) {
        String json;
        try {
            json = JSON.writeValueAsString(structuredContent);
        } catch (Exception e) {
            return ValidationResponse.asValid(String.valueOf(structuredContent));
        }
        try {
            Schema compiled = schemas.computeIfAbsent(schema, s -> registry.getSchema(JSON.valueToTree(s)));
            List<Error> errors = compiled.validate(JSON.readTree(json));
            if (!errors.isEmpty() && reported.putIfAbsent(schema, true) == null) {
                LOG.warn("A result differs from its tool's output schema (the API's spec), and was returned as it is: {}",
                        errors.stream().limit(3).map(Error::getMessage).toList());
            }
        } catch (Exception e) {
            // a schema the validator can't read: the result goes through unchecked
        }
        return ValidationResponse.asValid(json);
    }
}
