package ro.bitboy.mcpgp.runtime.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Environment variables as config (runtime-config §1.1): {@code <SERVER>_} prefix, {@code __} between
 * path segments, {@code <LIST>_<n>} for list items. A list set from the environment replaces the file's
 * list as a whole; scalar keys override one by one.
 */
final class EnvOverrides {

    /**
     * @param config   the merged config
     * @param fromEnv  JSON pointers of the values that came from environment variables
     * @param problems errors that stop startup (gaps in list indexes, values of the wrong type)
     * @param warnings prefixed variables that match no setting (they may be used by references)
     */
    record Result(ObjectNode config, Set<String> fromEnv, List<String> problems, List<String> warnings) {
    }

    private static final Pattern INDEXED = Pattern.compile("^(.+)_(\\d+)$");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final SchemaNav schema;

    EnvOverrides(SchemaNav schema) {
        this.schema = schema;
    }

    /** {@code orders-mcp} → {@code ORDERS_MCP_}. */
    static String prefix(String serverName) {
        return serverName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "_";
    }

    Result apply(ObjectNode base, Map<String, String> env, String prefix) {
        ObjectNode overlay = NODES.objectNode();
        Set<String> fromEnv = new TreeSet<>();
        Set<String> envLists = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Map.Entry<String, String> var : new TreeMap<>(env).entrySet()) {
            if (!var.getKey().startsWith(prefix) || var.getKey().length() == prefix.length()) {
                continue;
            }
            String name = var.getKey();
            List<Object> path = new ArrayList<>();
            JsonNode node = schema.root();
            boolean known = true;
            for (String segment : name.substring(prefix.length()).split("__", -1)) {
                Optional<Map.Entry<String, JsonNode>> property = schema.propertyForEnv(node, segment);
                if (property.isPresent()) {
                    path.add(property.get().getKey());
                    node = property.get().getValue();
                    continue;
                }
                Matcher indexed = INDEXED.matcher(segment);
                if (indexed.matches()) {
                    Optional<Map.Entry<String, JsonNode>> list = schema.propertyForEnv(node, indexed.group(1));
                    if (list.isPresent() && schema.type(list.get().getValue()).equals("array")) {
                        path.add(list.get().getKey());
                        path.add(Integer.parseInt(indexed.group(2)));
                        node = schema.items(list.get().getValue());
                        continue;
                    }
                }
                known = false;
                break;
            }
            if (!known) {
                warnings.add(name + " matches no setting; ignored");
                continue;
            }
            JsonNode value;
            try {
                value = typed(var.getValue(), schema.type(node));
            } catch (IllegalArgumentException e) {
                problems.add(name + ": " + e.getMessage());
                continue;
            }
            set(overlay, path, value, envLists);
            fromEnv.add(pointer(path));
        }

        for (String list : envLists) {
            JsonNode array = overlay.at(list);
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isNull()) {
                    problems.add("Environment list " + list + " has no item " + i + "; indexes must be contiguous from 0");
                }
            }
        }
        ObjectNode merged = base.deepCopy();
        merge(merged, overlay);
        return new Result(merged, fromEnv, problems, warnings);
    }

    private static JsonNode typed(String raw, String type) {
        return switch (type) {
            case "integer" -> {
                try {
                    yield NODES.numberNode(Long.parseLong(raw.strip()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("expected an integer");
                }
            }
            case "number" -> {
                try {
                    yield NODES.numberNode(Double.parseDouble(raw.strip()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("expected a number");
                }
            }
            case "boolean" -> switch (raw.strip().toLowerCase(Locale.ROOT)) {
                case "true" -> NODES.booleanNode(true);
                case "false" -> NODES.booleanNode(false);
                default -> throw new IllegalArgumentException("expected true or false");
            };
            default -> NODES.textNode(raw);
        };
    }

    private static void set(ObjectNode root, List<Object> path, JsonNode value, Set<String> envLists) {
        JsonNode current = root;
        for (int i = 0; i < path.size(); i++) {
            Object step = path.get(i);
            boolean last = i == path.size() - 1;
            Object next = last ? null : path.get(i + 1);
            if (step instanceof String key) {
                ObjectNode object = (ObjectNode) current;
                if (last) {
                    object.set(key, value);
                    return;
                }
                JsonNode child = object.get(key);
                if (child == null || child.isNull()) {
                    child = next instanceof Integer ? object.putArray(key) : object.putObject(key);
                }
                if (next instanceof Integer) {
                    envLists.add(pointer(path.subList(0, i + 1)));
                }
                current = child;
            } else {
                int index = (Integer) step;
                ArrayNode array = (ArrayNode) current;
                while (array.size() <= index) {
                    array.addNull();
                }
                if (last) {
                    array.set(index, value);
                    return;
                }
                JsonNode child = array.get(index);
                if (child.isNull()) {
                    child = next instanceof Integer ? NODES.arrayNode() : NODES.objectNode();
                    array.set(index, child);
                }
                current = child;
            }
        }
    }

    /** Objects merge key by key; lists and scalars from the overlay replace. */
    private static void merge(ObjectNode base, ObjectNode overlay) {
        Iterator<Map.Entry<String, JsonNode>> fields = overlay.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            JsonNode existing = base.get(f.getKey());
            if (existing instanceof ObjectNode existingObject && f.getValue() instanceof ObjectNode overlayObject) {
                merge(existingObject, overlayObject);
            } else {
                base.set(f.getKey(), f.getValue());
            }
        }
    }

    static String pointer(List<Object> path) {
        StringBuilder out = new StringBuilder();
        for (Object step : path) {
            out.append('/').append(step.toString().replace("~", "~0").replace("/", "~1"));
        }
        return out.toString();
    }
}
