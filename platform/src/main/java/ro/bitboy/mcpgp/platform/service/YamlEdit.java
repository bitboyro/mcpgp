package ro.bitboy.mcpgp.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import ro.bitboy.mcpgp.platform.api.ApiException;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Changes a definition file the way a person would (spec/platform-api.md §2.1, edits): the YAML is
 * edited as text at the positions the parser reports, so comments and formatting elsewhere stay as
 * they are. New values are written in flow style ({@code [a, b]}, {@code { k: v }}).
 *
 * <p>Paths are JSON Pointers into the document, plus {@code [key=value]} to pick the list item whose
 * {@code key} is {@code value} (created when missing), e.g. {@code /apis/[id=orders]/select/access}.
 * After editing, the result is parsed again and must equal the original data with the changes
 * applied; otherwise nothing is changed.
 */
public final class YamlEdit {

    /** {@code op} is {@code set} (with {@code value}; {@code null} is a value) or {@code remove}. */
    public record Change(String op, String path, JsonNode value) {
    }

    private sealed interface Segment permits Key, Select {
    }

    private record Key(String name) implements Segment {
    }

    private record Select(String key, String value) implements Segment {
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern SELECT = Pattern.compile("\\[([A-Za-z0-9_-]+)=([^\\]]*)]");
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_/$][A-Za-z0-9_./$@+-]*");
    private static final java.util.Set<String> RESERVED = java.util.Set.of("true", "false", "null", "yes", "no", "on", "off", "y", "n", "~");

    private YamlEdit() {
    }

    public static String apply(String text, List<Change> changes) {
        JsonNode expected = read(text);
        if (expected.isMissingNode() || expected.isNull()) {
            expected = JsonNodeFactory.instance.objectNode();
        }
        if (!expected.isObject()) {
            throw ApiException.validation("the definition is not a YAML mapping");
        }
        String edited = text;
        for (Change change : changes) {
            List<Segment> path = deprecatedAuthKey(expected, parse(change.path()));
            if ("set".equals(change.op())) {
                JsonNode value = change.value() == null ? JsonNodeFactory.instance.nullNode() : change.value();
                expected = setData((ObjectNode) expected, path, value);
                edited = setText(edited, path, value);
            } else if ("remove".equals(change.op())) {
                expected = removeData(expected, path);
                edited = removeText(edited, path);
            } else {
                throw ApiException.validation("op: set or remove (got " + change.op() + ")");
            }
        }
        JsonNode actual = read(edited);
        if (!actual.equals(expected)) {
            throw new ApiException(422, "VALIDATION", "these changes can't be made to the file without rewriting it; edit the file and upload it");
        }
        return edited;
    }

    // ---- paths

    static List<Segment> parse(String pointer) {
        if (pointer == null || !pointer.startsWith("/") || pointer.length() < 2) {
            throw ApiException.validation("path: a JSON Pointer such as /exposure/mode (got " + pointer + ")");
        }
        List<Segment> segments = new ArrayList<>();
        for (String raw : pointer.substring(1).split("/", -1)) {
            Matcher select = SELECT.matcher(raw);
            if (select.matches()) {
                segments.add(new Select(select.group(1), select.group(2)));
            } else if (raw.isEmpty()) {
                throw ApiException.validation("path " + pointer + " has an empty segment");
            } else {
                segments.add(new Key(raw.replace("~1", "/").replace("~0", "~")));
            }
        }
        return segments;
    }

    /**
     * An edit under an API's {@code upstreamAuth} goes to its {@code downstreamAuth} when the file still uses that
     * name (before 0.4.0), so the file never holds both; the compiler's warning asks for the rename.
     */
    static List<Segment> deprecatedAuthKey(JsonNode data, List<Segment> path) {
        for (int i = 1; i < path.size(); i++) {
            if (!(path.get(i) instanceof Key key) || !key.name().equals("upstreamAuth")) {
                continue;
            }
            JsonNode api = data;
            for (Segment segment : path.subList(0, i)) {
                api = api == null ? null : child(api, segment);
            }
            if (api != null && api.has("downstreamAuth") && !api.has("upstreamAuth")) {
                List<Segment> renamed = new ArrayList<>(path);
                renamed.set(i, new Key("downstreamAuth"));
                return renamed;
            }
            return path;
        }
        return path;
    }

    // ---- the data, for the check

    private static JsonNode read(String text) {
        try {
            JsonNode node = YAML.readTree(text);
            return node == null ? JsonNodeFactory.instance.missingNode() : node;
        } catch (IOException e) {
            throw ApiException.validation("the definition is not valid YAML: " + e.getMessage());
        }
    }

    private static ObjectNode setData(ObjectNode root, List<Segment> path, JsonNode value) {
        JsonNode current = root;
        for (int i = 0; i < path.size(); i++) {
            boolean last = i == path.size() - 1;
            Segment segment = path.get(i);
            if (segment instanceof Key key) {
                if (!current.isObject()) {
                    throw ApiException.validation("path: " + key.name() + " is not inside a mapping");
                }
                ObjectNode object = (ObjectNode) current;
                if (last) {
                    object.set(key.name(), value);
                    return root;
                }
                JsonNode child = object.get(key.name());
                if (child == null || !(child.isObject() || child.isArray())) {
                    child = path.get(i + 1) instanceof Select ? object.putArray(key.name()) : object.putObject(key.name());
                }
                current = child;
            } else {
                Select select = (Select) segment;
                if (!current.isArray()) {
                    throw ApiException.validation("path: [" + select.key() + "=…] picks an item of a list");
                }
                if (last) {
                    // the whole item: replaced, or added at the end
                    ObjectNode item = item(select, value);
                    ArrayNode array = (ArrayNode) current;
                    for (int n = 0; n < array.size(); n++) {
                        if (array.get(n).isObject() && select.value().equals(array.get(n).path(select.key()).asText(null))) {
                            array.set(n, item);
                            return root;
                        }
                    }
                    array.add(item);
                    return root;
                }
                JsonNode found = null;
                for (JsonNode item : current) {
                    if (item.isObject() && select.value().equals(item.path(select.key()).asText(null))) {
                        found = item;
                    }
                }
                if (found == null) {
                    found = ((ArrayNode) current).addObject().put(select.key(), select.value());
                }
                current = found;
            }
        }
        return root;
    }

    private static JsonNode removeData(JsonNode root, List<Segment> path) {
        JsonNode current = root;
        for (int i = 0; i < path.size() - 1; i++) {
            current = child(current, path.get(i));
            if (current == null) {
                return root;
            }
        }
        if (current instanceof ObjectNode object && path.getLast() instanceof Key key) {
            object.remove(key.name());
        } else if (current instanceof ArrayNode array && path.getLast() instanceof Select select) {
            for (int n = array.size() - 1; n >= 0; n--) {
                if (array.get(n).isObject() && select.value().equals(array.get(n).path(select.key()).asText(null))) {
                    array.remove(n);
                }
            }
        }
        return root;
    }

    /** A whole list item: a mapping, with the key it is picked by. */
    private static ObjectNode item(Select select, JsonNode value) {
        if (value == null || !value.isObject()) {
            throw ApiException.validation("path: a whole [" + select.key() + "=…] item is set to a mapping");
        }
        ObjectNode item = JsonNodeFactory.instance.objectNode().put(select.key(), select.value());
        value.properties().forEach(e -> {
            if (!e.getKey().equals(select.key())) {
                item.set(e.getKey(), e.getValue());
            }
        });
        return item;
    }

    private static JsonNode child(JsonNode node, Segment segment) {
        if (segment instanceof Key key) {
            return node.isObject() ? node.get(key.name()) : null;
        }
        Select select = (Select) segment;
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isObject() && select.value().equals(item.path(select.key()).asText(null))) {
                    return item;
                }
            }
        }
        return null;
    }

    // ---- the text

    /** Offsets of the text; the parser's marks are lines and code-point columns. */
    private record Text(String text, int[] lineStarts) {
        static Text of(String text) {
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    starts.add(i + 1);
                }
            }
            return new Text(text, starts.stream().mapToInt(Integer::intValue).toArray());
        }

        int offset(Mark mark) {
            if (mark.getLine() >= lineStarts.length) {
                return text.length();
            }
            int start = lineStarts[mark.getLine()];
            return Math.min(text.length(), text.offsetByCodePoints(start, Math.min(mark.getColumn(), text.codePointCount(start, lineEnd(start)))));
        }

        int lineEnd(int offset) {
            int end = text.indexOf('\n', offset);
            return end < 0 ? text.length() : end;
        }

        int lineStart(int offset) {
            int start = text.lastIndexOf('\n', Math.max(0, offset - 1));
            return offset == 0 ? 0 : start + 1;
        }
    }

    private static Node compose(String text) {
        LoaderOptions options = new LoaderOptions();
        options.setProcessComments(false);
        return new Yaml(options).compose(new StringReader(text));
    }

    private static String setText(String source, List<Segment> path, JsonNode value) {
        Node root = compose(source);
        Text text = Text.of(source);
        if (root == null) {
            return appendTopLevel(source, path, value);
        }
        if (!(root instanceof MappingNode)) {
            throw ApiException.validation("the definition is not a YAML mapping");
        }
        Node current = root;
        for (int i = 0; i < path.size(); i++) {
            List<Segment> rest = path.subList(i, path.size());
            if (current instanceof MappingNode mapping && rest.getFirst() instanceof Key key) {
                NodeTuple tuple = tuple(mapping, key.name());
                if (tuple == null) {
                    return insertIntoMapping(text, mapping, mapping == root, rest, value);
                }
                if (rest.size() == 1) {
                    return replace(text, tuple.getValueNode(), render(value));
                }
                Node next = tuple.getValueNode();
                if (!(next instanceof MappingNode || next instanceof SequenceNode)) {
                    // a scalar (often null) where a collection should be: write the rest in its place
                    return replace(text, next, render(nested(rest.subList(1, rest.size()), value)));
                }
                current = next;
            } else if (current instanceof SequenceNode sequence && rest.getFirst() instanceof Select select) {
                Node item = item(sequence, select);
                if (rest.size() == 1) {
                    ObjectNode whole = item(select, value);
                    return item == null ? appendItem(text, sequence, whole) : replace(text, item, render(whole));
                }
                if (item == null) {
                    JsonNode created = nested(rest.subList(1, rest.size()), value);
                    ObjectNode newItem = JsonNodeFactory.instance.objectNode().put(select.key(), select.value());
                    newItem.setAll((ObjectNode) created);
                    return appendItem(text, sequence, newItem);
                }
                current = item;
            } else {
                throw ApiException.validation("path " + describe(path) + " doesn't fit the file's structure");
            }
        }
        return source;
    }

    private static String removeText(String source, List<Segment> path) {
        Node root = compose(source);
        if (root == null) {
            return source;
        }
        Text text = Text.of(source);
        Node current = root;
        for (int i = 0; i < path.size() - 1; i++) {
            current = current instanceof MappingNode m && path.get(i) instanceof Key k ? valueOf(m, k.name())
                    : current instanceof SequenceNode s && path.get(i) instanceof Select sel ? item(s, sel) : null;
            if (current == null) {
                return source;
            }
        }
        if (current instanceof SequenceNode sequence && path.getLast() instanceof Select select) {
            Node item = item(sequence, select);
            if (item == null) {
                return source;
            }
            if (sequence.getFlowStyle() == DumperOptions.FlowStyle.FLOW || sequence.getValue().size() == 1) {
                ArrayNode data = (ArrayNode) read(slice(text, sequence));
                for (int n = data.size() - 1; n >= 0; n--) {
                    if (select.value().equals(data.get(n).path(select.key()).asText(null))) {
                        data.remove(n);
                    }
                }
                return replace(text, sequence, render(data));
            }
            int itemStart = text.offset(item.getStartMark());
            int start = text.lineStart(source.lastIndexOf('-', itemStart));
            int end = Math.min(source.length(), text.lineEnd(endOf(text, item)) + 1);
            return source.substring(0, start) + source.substring(end);
        }
        if (!(current instanceof MappingNode mapping) || !(path.getLast() instanceof Key key)) {
            return source;
        }
        NodeTuple tuple = tuple(mapping, key.name());
        if (tuple == null) {
            return source;
        }
        if (mapping.getFlowStyle() == DumperOptions.FlowStyle.FLOW || mapping.getValue().size() == 1) {
            // flow mappings are short: write them again; an emptied block mapping becomes {}
            ObjectNode data = (ObjectNode) read(slice(text, mapping));
            data.remove(key.name());
            return replace(text, mapping, render(data));
        }
        int start = text.lineStart(text.offset(tuple.getKeyNode().getStartMark()));
        int end = text.lineEnd(endOf(text, tuple.getValueNode()));
        end = Math.min(source.length(), end + 1);
        return source.substring(0, start) + source.substring(end);
    }

    private static String insertIntoMapping(Text text, MappingNode mapping, boolean top, List<Segment> rest, JsonNode value) {
        Key key = (Key) rest.getFirst();
        JsonNode inner = rest.size() == 1 ? value : nested(rest.subList(1, rest.size()), value);
        if (mapping.getFlowStyle() == DumperOptions.FlowStyle.FLOW || mapping.getValue().isEmpty()) {
            ObjectNode data = mapping.getValue().isEmpty() ? JsonNodeFactory.instance.objectNode() : (ObjectNode) read(slice(text, mapping));
            data.set(key.name(), inner);
            return replace(text, mapping, render(data));
        }
        Node firstKey = mapping.getValue().getFirst().getKeyNode();
        String indent = " ".repeat(firstKey.getStartMark().getColumn());
        int after = text.lineEnd(endOf(text, mapping.getValue().getLast().getValueNode()));
        String line = indent + scalar(key.name()) + ": " + render(inner);
        String source = text.text();
        return source.substring(0, after) + "\n" + line + source.substring(after);
    }

    private static String appendTopLevel(String source, List<Segment> path, JsonNode value) {
        JsonNode data = nested(path, value);
        StringBuilder out = new StringBuilder(source);
        if (!source.isEmpty() && !source.endsWith("\n")) {
            out.append('\n');
        }
        data.properties().forEach(e -> out.append(scalar(e.getKey())).append(": ").append(render(e.getValue())).append('\n'));
        return out.toString();
    }

    private static String appendItem(Text text, SequenceNode sequence, JsonNode item) {
        if (sequence.getFlowStyle() == DumperOptions.FlowStyle.FLOW || sequence.getValue().isEmpty()) {
            ArrayNode data = sequence.getValue().isEmpty() ? JsonNodeFactory.instance.arrayNode() : (ArrayNode) read(slice(text, sequence));
            data.add(item);
            return replace(text, sequence, render(data));
        }
        Node first = sequence.getValue().getFirst();
        int itemStart = text.offset(first.getStartMark());
        int dash = text.text().lastIndexOf('-', itemStart);
        String indent = " ".repeat(Math.max(0, dash - text.lineStart(dash)));
        int after = text.lineEnd(endOf(text, sequence.getValue().getLast()));
        String source = text.text();
        return source.substring(0, after) + "\n" + indent + "- " + render(item) + source.substring(after);
    }

    private static String replace(Text text, Node node, String replacement) {
        int start = text.offset(node.getStartMark());
        int end = endOf(text, node);
        String source = text.text();
        if (node instanceof ScalarNode scalar && scalar.getValue().isEmpty() && start == end) {
            // an empty value (`key:`): write after the colon
            return source.substring(0, start) + (start > 0 && source.charAt(start - 1) == ':' ? " " : "") + replacement + source.substring(end);
        }
        return source.substring(0, start) + replacement + source.substring(end);
    }

    /** Where a node's own text ends: block collections end with their last child, not at the next key. */
    private static int endOf(Text text, Node node) {
        if (node instanceof MappingNode mapping && mapping.getFlowStyle() != DumperOptions.FlowStyle.FLOW && !mapping.getValue().isEmpty()) {
            return endOf(text, mapping.getValue().getLast().getValueNode());
        }
        if (node instanceof SequenceNode sequence && sequence.getFlowStyle() != DumperOptions.FlowStyle.FLOW && !sequence.getValue().isEmpty()) {
            return endOf(text, sequence.getValue().getLast());
        }
        int end = text.offset(node.getEndMark());
        // block scalars (| and >) end after their line break
        while (end > 0 && (text.text().charAt(end - 1) == '\n') && node instanceof ScalarNode s && s.getScalarStyle() != DumperOptions.ScalarStyle.PLAIN) {
            end--;
        }
        return end;
    }

    private static String slice(Text text, Node node) {
        return text.text().substring(text.offset(node.getStartMark()), endOf(text, node));
    }

    private static NodeTuple tuple(MappingNode mapping, String key) {
        for (NodeTuple tuple : mapping.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode k && k.getValue().equals(key)) {
                return tuple;
            }
        }
        return null;
    }

    private static Node valueOf(MappingNode mapping, String key) {
        NodeTuple tuple = tuple(mapping, key);
        return tuple == null ? null : tuple.getValueNode();
    }

    private static Node item(SequenceNode sequence, Select select) {
        for (Node item : sequence.getValue()) {
            if (item instanceof MappingNode m && valueOf(m, select.key()) instanceof ScalarNode s && s.getValue().equals(select.value())) {
                return item;
            }
        }
        return null;
    }

    /** The rest of a path as data: {@code /a/b} and {@code v} → {@code {a: {b: v}}}. */
    private static JsonNode nested(List<Segment> path, JsonNode value) {
        JsonNode result = value;
        for (int i = path.size() - 1; i >= 0; i--) {
            if (path.get(i) instanceof Select select && i == path.size() - 1) {
                result = JsonNodeFactory.instance.arrayNode().add(item(select, value));
                continue;
            }
            if (!(path.get(i) instanceof Key key)) {
                throw ApiException.validation("path: a new [key=value] item must be followed by its keys");
            }
            ObjectNode wrap = JsonNodeFactory.instance.objectNode();
            wrap.set(key.name(), result);
            result = wrap;
        }
        return result;
    }

    // ---- rendering, flow style

    static String render(JsonNode value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isObject()) {
            if (value.isEmpty()) {
                return "{}";
            }
            StringBuilder out = new StringBuilder("{ ");
            Iterator<Map.Entry<String, JsonNode>> fields = value.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                out.append(scalar(field.getKey())).append(": ").append(render(field.getValue()));
                if (fields.hasNext()) {
                    out.append(", ");
                }
            }
            return out.append(" }").toString();
        }
        if (value.isArray()) {
            List<String> items = new ArrayList<>();
            value.forEach(v -> items.add(render(v)));
            return "[" + String.join(", ", items) + "]";
        }
        if (value.isTextual()) {
            return scalar(value.asText());
        }
        return value.asText();
    }

    static String scalar(String text) {
        if (PLAIN.matcher(text).matches() && !RESERVED.contains(text.toLowerCase(Locale.ROOT))) {
            return text;
        }
        try {
            return new ObjectMapper().writeValueAsString(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String describe(List<Segment> path) {
        StringBuilder out = new StringBuilder();
        for (Segment s : path) {
            out.append('/').append(s instanceof Key k ? k.name() : "[" + ((Select) s).key() + "=" + ((Select) s).value() + "]");
        }
        return out.toString();
    }
}
