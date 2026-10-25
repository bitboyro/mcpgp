package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Fields whose values are replaced with {@code ***} wherever an upstream answer is shown or kept: tool results,
 * error bodies (the agent's and the audit file's) and recordings (audit-and-errors.md, Redaction). The names are
 * the defaults plus the definition's {@code runtimeDefaults.redaction.fields}, matched case-insensitively, at any
 * depth of a JSON body.
 */
public final class MaskedFields {

    /** Always masked, whatever the definition lists. */
    public static final List<String> DEFAULTS = List.of("password", "secret", "token", "apiKey", "authorization");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TextNode MASK = TextNode.valueOf("***");

    private final Set<String> names;
    /** For bodies that don't parse (cut off at the size limit): "name": value pairs with a string, number, boolean or null. */
    private final Pattern pair;

    public MaskedFields(Iterable<String> extra) {
        Set<String> all = new TreeSet<>();
        DEFAULTS.forEach(n -> all.add(n.toLowerCase(Locale.ROOT)));
        extra.forEach(n -> { if (n != null && !n.isBlank()) all.add(n.toLowerCase(Locale.ROOT)); });
        this.names = Set.copyOf(all);
        this.pair = Pattern.compile("(\"(?i:" + all.stream().map(Pattern::quote).collect(Collectors.joining("|"))
                + ")\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|-?[0-9][0-9.eE+-]*|true|false|null)");
    }

    public static MaskedFields of(Manifest manifest) {
        List<String> extra = new java.util.ArrayList<>();
        manifest.policies().at("/redaction/fields").forEach(f -> extra.add(f.asText()));
        return new MaskedFields(extra);
    }

    /** The names, lower case. */
    public Set<String> names() {
        return names;
    }

    public boolean masks(String field) {
        return field != null && names.contains(field.toLowerCase(Locale.ROOT));
    }

    /** A JSON tree with the masked fields' values replaced, at any depth; the same node when nothing matched. */
    public JsonNode mask(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode copy = null;
            for (var it = object.properties().iterator(); it.hasNext(); ) {
                var field = it.next();
                JsonNode value = masks(field.getKey()) && !field.getValue().isNull() ? MASK : mask(field.getValue());
                if (value != field.getValue()) {
                    if (copy == null) copy = object.deepCopy();
                    copy.set(field.getKey(), value);
                }
            }
            return copy == null ? object : copy;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = null;
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = mask(array.get(i));
                if (value != array.get(i)) {
                    if (copy == null) copy = array.deepCopy();
                    copy.set(i, value);
                }
            }
            return copy == null ? array : copy;
        }
        return node;
    }

    /** JSON text with the masked fields' values replaced: the whole tree when it parses, simple pairs when it doesn't. */
    public String maskText(String text) {
        String trimmed = text.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode tree = JSON.readTree(text);
                JsonNode masked = mask(tree);
                return masked == tree ? text : masked.toString();
            } catch (IOException e) {
                // cut off or not JSON: the pairs below
            }
        }
        Matcher m = pair.matcher(text);
        return m.find() ? m.replaceAll(r -> Matcher.quoteReplacement(r.group(1) + "\"***\"")) : text;
    }
}
