package ro.bitboy.mcpgp.core.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import ro.bitboy.mcpgp.core.diag.Codes;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.diag.Location;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies OpenAPI Overlay 1.0 documents: each action selects nodes with a JSONPath {@code target} and
 * either {@code update}s them (objects merge recursively, arrays get the update appended) or
 * {@code remove}s them. Actions run in order.
 */
final class Overlays {

    private static final Configuration PATHS = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .options(Option.AS_PATH_LIST, Option.SUPPRESS_EXCEPTIONS)
            .build();

    private static final Pattern SEGMENT = Pattern.compile("\\[(?:'((?:[^'\\\\]|\\\\.)*)'|(\\d+))]");

    private Overlays() {
    }

    static void apply(ObjectNode document, JsonNode overlay, Diagnostics diagnostics, Location location) {
        if (!overlay.path("overlay").asText().startsWith("1.")) {
            diagnostics.error(Codes.OVERLAY_INVALID, "Not an OpenAPI Overlay 1.x document", location);
            return;
        }
        JsonNode actions = overlay.path("actions");
        for (int i = 0; i < actions.size(); i++) {
            JsonNode action = actions.get(i);
            Location actionLocation = location.withPointer("/actions/" + i);
            String target = action.path("target").asText(null);
            if (target == null) {
                diagnostics.error(Codes.OVERLAY_INVALID, "Action has no target", actionLocation);
                continue;
            }
            List<String> paths = select(document, target);
            if (paths.isEmpty()) {
                diagnostics.warning(Codes.OVERLAY_TARGET_MATCHES_NOTHING, "Target '" + target + "' matches nothing", actionLocation);
                continue;
            }
            boolean remove = action.path("remove").asBoolean(false);
            JsonNode update = action.get("update");
            // remove from the end so indexes of earlier matches stay valid
            for (int p = paths.size() - 1; p >= 0; p--) {
                List<Object> segments = segments(paths.get(p));
                if (remove) {
                    removeAt(document, segments);
                } else if (update != null) {
                    JsonNode node = nodeAt(document, segments);
                    if (node != null) {
                        updateInPlace(node, update);
                    }
                }
            }
        }
    }

    private static List<String> select(JsonNode document, String target) {
        List<String> result = new ArrayList<>();
        try {
            JsonNode found = JsonPath.using(PATHS).parse(document).read(target);
            if (found != null && found.isArray()) {
                found.forEach(n -> result.add(n.asText()));
            }
        } catch (RuntimeException e) {
            // invalid expression: treated as matching nothing; reported by the caller
        }
        return result;
    }

    /** "$['paths']['/orders']['get']" or "$['tags'][2]" → ["paths", "/orders", "get"] / ["tags", 2]. */
    private static List<Object> segments(String path) {
        List<Object> segments = new ArrayList<>();
        Matcher m = SEGMENT.matcher(path);
        while (m.find()) {
            if (m.group(1) != null) {
                segments.add(m.group(1).replace("\\'", "'"));
            } else {
                segments.add(Integer.parseInt(m.group(2)));
            }
        }
        return segments;
    }

    private static JsonNode nodeAt(JsonNode root, List<Object> segments) {
        JsonNode node = root;
        for (Object segment : segments) {
            node = segment instanceof Integer index ? node.get(index) : node.get((String) segment);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    private static void removeAt(JsonNode root, List<Object> segments) {
        if (segments.isEmpty()) {
            return;
        }
        JsonNode parent = nodeAt(root, segments.subList(0, segments.size() - 1));
        Object last = segments.get(segments.size() - 1);
        if (parent instanceof ObjectNode object && last instanceof String name) {
            object.remove(name);
        } else if (parent instanceof ArrayNode array && last instanceof Integer index) {
            array.remove(index);
        }
    }

    private static void updateInPlace(JsonNode target, JsonNode update) {
        if (target instanceof ObjectNode object && update.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = update.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode existing = object.get(field.getKey());
                if (existing != null && (existing.isObject() || existing.isArray()) && field.getValue().isContainerNode()) {
                    updateInPlace(existing, field.getValue());
                } else {
                    object.set(field.getKey(), field.getValue().deepCopy());
                }
            }
        } else if (target instanceof ArrayNode array) {
            if (update.isArray()) {
                update.forEach(item -> array.add(item.deepCopy()));
            } else {
                array.add(update.deepCopy());
            }
        }
    }
}
