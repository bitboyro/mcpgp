package ro.bitboy.mcpgp.runtime.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Walks the runtime-config JSON Schema by config path: follows {@code $ref}, merges the branches of
 * {@code oneOf} / {@code anyOf} / {@code allOf}, and knows maps ({@code additionalProperties}) and lists
 * ({@code items}). Used to map environment variable names back to config keys and to find secret fields.
 */
final class SchemaNav {

    private static final JsonNode EMPTY = JsonNodeFactory.instance.objectNode();

    private final JsonNode root;

    SchemaNav(JsonNode root) {
        this.root = root;
    }

    JsonNode root() {
        return root;
    }

    /** The schema with references followed and composition branches merged into one view. */
    JsonNode resolve(JsonNode schema) {
        JsonNode current = schema;
        for (int i = 0; i < 16 && current != null && current.has("$ref"); i++) {
            String ref = current.get("$ref").asText();
            current = ref.startsWith("#") ? root.at(ref.substring(1)) : EMPTY;
        }
        if (current == null || current.isMissingNode()) {
            return EMPTY;
        }
        boolean composed = current.has("oneOf") || current.has("anyOf") || current.has("allOf");
        if (!composed) {
            return current;
        }
        ObjectNode merged = current.deepCopy();
        ObjectNode properties = merged.has("properties") ? (ObjectNode) merged.get("properties") : merged.putObject("properties");
        for (String key : new String[] {"oneOf", "anyOf", "allOf"}) {
            for (JsonNode branch : current.path(key)) {
                JsonNode resolved = resolve(branch);
                resolved.path("properties").fields().forEachRemaining(f -> {
                    if (!properties.has(f.getKey())) {
                        properties.set(f.getKey(), f.getValue());
                    }
                });
                if (!merged.has("type") && resolved.has("type")) {
                    merged.set("type", resolved.get("type"));
                }
                if (!merged.has("items") && resolved.has("items")) {
                    merged.set("items", resolved.get("items")); // a value that is one thing or a list of them
                }
            }
        }
        return merged;
    }

    /** The schema of property {@code name}, or of a map entry; empty when the schema doesn't allow it. */
    Optional<JsonNode> property(JsonNode schema, String name) {
        JsonNode resolved = resolve(schema);
        JsonNode property = resolved.path("properties").get(name);
        if (property != null) {
            return Optional.of(property);
        }
        JsonNode additional = resolved.get("additionalProperties");
        return additional != null && additional.isObject() ? Optional.of(additional) : Optional.empty();
    }

    /** The real key for an environment segment (upper case, no separators), and its schema. */
    Optional<Map.Entry<String, JsonNode>> propertyForEnv(JsonNode schema, String segment) {
        JsonNode resolved = resolve(schema);
        Iterator<Map.Entry<String, JsonNode>> fields = resolved.path("properties").fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            if (f.getKey().toUpperCase(Locale.ROOT).equals(segment)) {
                return Optional.of(f);
            }
        }
        JsonNode additional = resolved.get("additionalProperties");
        if (additional != null && additional.isObject()) {
            return Optional.of(Map.entry(segment.toLowerCase(Locale.ROOT), additional));
        }
        return Optional.empty();
    }

    JsonNode items(JsonNode schema) {
        return resolve(schema).path("items");
    }

    String type(JsonNode schema) {
        JsonNode type = resolve(schema).path("type");
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (!t.asText().equals("null")) {
                    return t.asText();
                }
            }
        }
        return type.asText("");
    }

    boolean isSecret(JsonNode schema) {
        return resolve(schema).path("x-mcpgp-secret").asBoolean(false);
    }
}
