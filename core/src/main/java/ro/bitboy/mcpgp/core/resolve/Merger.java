package ro.bitboy.mcpgp.core.resolve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.core.diag.Codes;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.diag.Location;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges a later document onto an earlier one (definition-format §7):
 * <ul>
 *   <li>objects merge deeply; later values win; {@code null} removes an inherited key;</li>
 *   <li>lists of objects that all have an {@code id} (or all a {@code version}) merge item by item on
 *       that key; {@code $remove: true} removes an inherited item; new items are appended;</li>
 *   <li>every other list replaces the earlier one.</li>
 * </ul>
 * Inputs are not modified.
 */
public final class Merger {

    static final String REMOVE = "$remove";
    private static final List<String> KEYS = List.of("id", "version");

    private final Diagnostics diagnostics;
    private final Location overlayLocation;

    public Merger(Diagnostics diagnostics, Location overlayLocation) {
        this.diagnostics = diagnostics;
        this.overlayLocation = overlayLocation;
    }

    public JsonNode merge(JsonNode base, JsonNode overlay) {
        return merge(base, overlay, "");
    }

    private JsonNode merge(JsonNode base, JsonNode overlay, String pointer) {
        if (base == null || base.isMissingNode()) {
            return strip(overlay.deepCopy());
        }
        if (base.isObject() && overlay.isObject()) {
            return mergeObjects((ObjectNode) base, (ObjectNode) overlay, pointer);
        }
        if (base.isArray() && overlay.isArray()) {
            String key = commonKey((ArrayNode) base, (ArrayNode) overlay);
            return key == null ? strip(overlay.deepCopy()) : mergeKeyed((ArrayNode) base, (ArrayNode) overlay, key, pointer);
        }
        if (base.getNodeType() != overlay.getNodeType() && !(base.isValueNode() && overlay.isValueNode())) {
            diagnostics.warning(Codes.MERGE_TYPE_CONFLICT,
                    "Replacing a " + kind(base) + " with a " + kind(overlay), overlayLocation.withPointer(pointer));
        }
        return strip(overlay.deepCopy());
    }

    private ObjectNode mergeObjects(ObjectNode base, ObjectNode overlay, String pointer) {
        ObjectNode result = base.deepCopy();
        Iterator<Map.Entry<String, JsonNode>> fields = overlay.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            JsonNode value = field.getValue();
            if (value.isNull()) {
                result.remove(name);
            } else {
                result.set(name, merge(result.get(name), value, pointer + "/" + escape(name)));
            }
        }
        return result;
    }

    private ArrayNode mergeKeyed(ArrayNode base, ArrayNode overlay, String key, String pointer) {
        Map<String, JsonNode> items = new LinkedHashMap<>();
        for (JsonNode item : base) {
            items.put(item.get(key).asText(), item);
        }
        int index = 0;
        for (JsonNode item : overlay) {
            String id = item.get(key).asText();
            String itemPointer = pointer + "/" + index++;
            if (item.path(REMOVE).asBoolean(false)) {
                items.remove(id);
            } else if (items.containsKey(id)) {
                items.put(id, merge(items.get(id), item, itemPointer));
            } else {
                items.put(id, strip(item.deepCopy()));
            }
        }
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        items.values().forEach(result::add);
        return result;
    }

    /** The key both lists use for every item, or null when they don't qualify for keyed merging. */
    private static String commonKey(ArrayNode base, ArrayNode overlay) {
        for (String key : KEYS) {
            if (allHave(base, key) && allHave(overlay, key)) {
                return key;
            }
        }
        return null;
    }

    private static boolean allHave(ArrayNode array, String key) {
        if (array.isEmpty()) {
            return true;
        }
        for (JsonNode item : array) {
            if (!item.isObject() || !item.hasNonNull(key) || !item.get(key).isValueNode()) {
                return false;
            }
        }
        return true;
    }

    /** Removes merge directives ({@code $remove}) and keys set to null from a node that is copied as is. */
    static JsonNode strip(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.remove(REMOVE);
            List<String> nulls = new ArrayList<>();
            object.fields().forEachRemaining(f -> {
                if (f.getValue().isNull()) {
                    nulls.add(f.getKey());
                } else {
                    strip(f.getValue());
                }
            });
            nulls.forEach(object::remove);
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = array.size() - 1; i >= 0; i--) {
                if (array.get(i).path(REMOVE).asBoolean(false)) {
                    array.remove(i);
                } else {
                    strip(array.get(i));
                }
            }
        }
        return node;
    }

    private static String kind(JsonNode node) {
        return node.isObject() ? "map" : node.isArray() ? "list" : "value";
    }

    static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
