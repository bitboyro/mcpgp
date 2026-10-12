package ro.bitboy.mcpgp.core.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Format;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Converts OpenAPI schemas (Swagger 2.0, OAS 3.0, OAS 3.1) to JSON Schema 2020-12 (tools-contract §2):
 * {@code nullable} becomes a {@code null} type, boolean exclusive bounds become numeric, {@code example}
 * becomes {@code examples}, OpenAPI-only keywords are dropped, and {@code readOnly} (for inputs) or
 * {@code writeOnly} (for outputs) properties are removed.
 *
 * <p>References to shared schemas are inlined up to {@link #INLINE_DEPTH} levels; deeper or recursive
 * ones go to {@code $defs}, so every produced schema is self-contained.
 */
public final class SchemaShaper {

    /** Which side of the call a schema describes: what the agent sends, or what it receives. */
    public enum Direction { INPUT, OUTPUT, BOTH }

    static final int INLINE_DEPTH = 5;
    /** Shared schemas bigger than this (in JSON nodes, with what they refer to inlined) go to {@code $defs}, below the top level. */
    static final int INLINE_SIZE = 300;
    private static final String COMPONENTS = "#/components/schemas/";
    private static final Set<String> DROPPED = Set.of(
            "discriminator", "xml", "externalDocs", "nullable", "x-nullable", "example");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Map<String, ObjectNode> shared;
    private final Format format;
    /** When set, shared references are rewritten to {@code <refPrefix><Name>} instead of inlined. */
    private final String refPrefix;
    /** Shared schemas' sizes with their references inlined, capped just above {@link #INLINE_SIZE}. */
    private final Map<String, Integer> sizes = new java.util.HashMap<>();
    private final Set<String> measuring = new java.util.HashSet<>();

    public SchemaShaper(Map<String, ObjectNode> shared, Format format) {
        this(shared, format, null);
    }

    private SchemaShaper(Map<String, ObjectNode> shared, Format format, String refPrefix) {
        this.shared = shared;
        this.format = format;
        this.refPrefix = refPrefix;
    }

    /** A self-contained schema: references inlined or placed in its own {@code $defs}. */
    public ObjectNode selfContained(JsonNode schema, Direction direction) {
        Map<String, JsonNode> defs = new TreeMap<>();
        ObjectNode result = inline(schema, direction, defs);
        if (!defs.isEmpty()) {
            ObjectNode defsNode = NODES.objectNode();
            defs.forEach(defsNode::set);
            result.set("$defs", defsNode);
        }
        return result;
    }

    /**
     * Like {@link #selfContained}, but definitions that can't be inlined are added to {@code defs}
     * (referenced as {@code #/$defs/<Name>}), so several schemas can share one {@code $defs} section.
     */
    public ObjectNode inline(JsonNode schema, Direction direction, Map<String, JsonNode> defs) {
        return asObject(convert(schema, direction, 0, new ArrayDeque<>(), defs));
    }

    /** Follows top-level references to shared schemas; returns the schema itself when it has none. */
    public JsonNode dereference(JsonNode schema) {
        JsonNode current = schema;
        for (int i = 0; i < INLINE_DEPTH && current != null && current.path("$ref").asText("").startsWith(COMPONENTS); i++) {
            current = shared.get(current.get("$ref").asText().substring(COMPONENTS.length()));
        }
        return current == null ? NODES.objectNode() : current;
    }

    /**
     * A schema whose shared references point to {@code <prefix><Name>} (used for the manifest's
     * shared {@code schemas} section and the variants), converted but not inlined.
     */
    public ObjectNode referencing(JsonNode schema, Direction direction, String prefix) {
        return asObject(new SchemaShaper(shared, format, prefix).convert(schema, direction, 0, new ArrayDeque<>(), new TreeMap<>()));
    }

    // ---------------------------------------------------------------------- conversion

    private JsonNode convert(JsonNode schema, Direction direction, int depth, Deque<String> stack, Map<String, JsonNode> defs) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return NODES.objectNode();
        }
        if (!schema.isObject()) {
            return schema.deepCopy();
        }
        JsonNode ref = schema.get("$ref");
        if (ref != null && ref.isTextual() && ref.asText().startsWith(COMPONENTS)) {
            String name = ref.asText().substring(COMPONENTS.length());
            ObjectNode target = shared.get(name);
            if (refPrefix != null) {
                ObjectNode rewritten = asObject(schema.deepCopy());
                rewritten.put("$ref", refPrefix + name);
                return rewritten;
            }
            if (target == null) {
                return NODES.objectNode().put("description", "Unknown schema " + name);
            }
            ObjectNode resolved;
            if (stack.contains(name) || depth >= INLINE_DEPTH || depth > 0 && expandedSize(name) > INLINE_SIZE) {
                if (!defs.containsKey(name)) {
                    defs.put(name, NODES.objectNode()); // placeholder against recursion
                    Deque<String> own = new ArrayDeque<>();
                    own.push(name);
                    defs.put(name, convert(target, direction, 0, own, defs));
                }
                resolved = NODES.objectNode().put("$ref", "#/$defs/" + name);
            } else {
                stack.push(name);
                resolved = asObject(convert(target, direction, depth + 1, stack, defs));
                stack.pop();
            }
            // sibling keywords next to $ref (description, etc.) are kept
            ObjectNode siblings = asObject(schema.deepCopy());
            siblings.remove("$ref");
            if (!siblings.isEmpty()) {
                ObjectNode merged = resolved.deepCopy();
                merged.setAll(asObject(normalize(siblings, direction, depth, stack, defs)));
                return merged;
            }
            return resolved;
        }
        return normalize((ObjectNode) schema.deepCopy(), direction, depth, stack, defs);
    }

    /**
     * How big a shared schema gets with every reference in it inlined: a wide schema five levels deep is
     * thousands of copies, so past {@link #INLINE_SIZE} it's referenced instead. A reference back to a
     * schema being measured counts as one node: it's a {@code $defs} reference once inlined.
     */
    private int expandedSize(String name) {
        Integer known = sizes.get(name);
        if (known != null) {
            return known;
        }
        if (!measuring.add(name)) {
            return 1;
        }
        int size = size(shared.get(name));
        measuring.remove(name);
        sizes.put(name, Math.min(size, INLINE_SIZE + 1));
        return sizes.get(name);
    }

    private int size(JsonNode node) {
        if (node == null) {
            return 0;
        }
        JsonNode ref = node.get("$ref");
        if (ref != null && ref.isTextual() && ref.asText().startsWith(COMPONENTS)) {
            return expandedSize(ref.asText().substring(COMPONENTS.length()));
        }
        int size = 1;
        for (JsonNode child : node) {
            size += size(child);
            if (size > INLINE_SIZE) {
                return size;
            }
        }
        return size;
    }

    private ObjectNode normalize(ObjectNode schema, Direction direction, int depth, Deque<String> stack, Map<String, JsonNode> defs) {
        boolean nullable = schema.path("nullable").asBoolean(false) || schema.path("x-nullable").asBoolean(false);
        if (schema.has("example") && !schema.has("examples")) {
            schema.set("examples", NODES.arrayNode().add(schema.get("example")));
        }
        DROPPED.forEach(schema::remove);
        if (nullable && schema.has("type") && schema.get("type").isTextual()) {
            schema.set("type", NODES.arrayNode().add(schema.get("type").asText()).add("null"));
        }
        fixExclusive(schema, "exclusiveMinimum", "minimum");
        fixExclusive(schema, "exclusiveMaximum", "maximum");
        if (format != Format.OPENAPI_3_1 && schema.path("type").asText().equals("file")) {
            schema.put("type", "string");
            schema.put("format", "binary");
        }

        // children
        JsonNode properties = schema.get("properties");
        if (properties instanceof ObjectNode props) {
            ObjectNode converted = NODES.objectNode();
            List<String> removed = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                if (hiddenFor(f.getValue(), direction)) {
                    removed.add(f.getKey());
                    continue;
                }
                converted.set(f.getKey(), convert(f.getValue(), direction, depth, stack, defs));
            }
            schema.set("properties", converted);
            if (schema.get("required") instanceof ArrayNode required) {
                ArrayNode kept = NODES.arrayNode();
                required.forEach(r -> {
                    if (!removed.contains(r.asText())) {
                        kept.add(r);
                    }
                });
                if (kept.isEmpty()) {
                    schema.remove("required");
                } else {
                    schema.set("required", kept);
                }
            }
        }
        for (String key : List.of("items", "additionalProperties", "not", "contains", "propertyNames")) {
            JsonNode child = schema.get(key);
            if (child != null && child.isObject()) {
                schema.set(key, convert(child, direction, depth, stack, defs));
            }
        }
        for (String key : List.of("allOf", "anyOf", "oneOf", "prefixItems")) {
            JsonNode list = schema.get(key);
            if (list instanceof ArrayNode array) {
                ArrayNode converted = NODES.arrayNode();
                array.forEach(item -> converted.add(convert(item, direction, depth, stack, defs)));
                schema.set(key, converted);
            }
        }
        return schema;
    }

    /** Properties the agent shouldn't see: readOnly ones in inputs, writeOnly ones in outputs. */
    private boolean hiddenFor(JsonNode property, Direction direction) {
        JsonNode effective = property;
        if (property.has("$ref") && property.get("$ref").asText().startsWith(COMPONENTS)) {
            ObjectNode target = shared.get(property.get("$ref").asText().substring(COMPONENTS.length()));
            if (target != null && !property.has("readOnly") && !property.has("writeOnly")) {
                effective = target;
            }
        }
        return switch (direction) {
            case INPUT -> effective.path("readOnly").asBoolean(false);
            case OUTPUT -> effective.path("writeOnly").asBoolean(false);
            case BOTH -> false;
        };
    }

    private static void fixExclusive(ObjectNode schema, String exclusive, String bound) {
        JsonNode flag = schema.get(exclusive);
        if (flag != null && flag.isBoolean()) {
            schema.remove(exclusive);
            if (flag.asBoolean() && schema.has(bound)) {
                schema.set(exclusive, schema.remove(bound));
            }
        }
    }

    private static ObjectNode asObject(JsonNode node) {
        return node instanceof ObjectNode o ? o : NODES.objectNode();
    }
}
