package ro.bitboy.mcpgp.runtime.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Edits the operator's config file for the {@code init} and {@code instance add/remove} commands.
 * YAML is edited as text, using the parser's positions, so comments and formatting elsewhere stay as
 * they are; JSON (which has no comments) is rewritten. Values for {@code .env} are appended there,
 * owner-only. Every write keeps a {@code .bak} copy of the previous file.
 */
public final class ConfigFile {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;

    public ConfigFile(Path file) {
        this.file = file;
    }

    public Path path() {
        return file;
    }

    private boolean json() {
        return file.getFileName().toString().toLowerCase().endsWith(".json");
    }

    /**
     * Appends an instance to the instances list at {@code listPath} (e.g. {@code ["instances"]} or
     * {@code ["apis", "orders", "instances"]}); creates the list when missing.
     */
    public void addInstance(List<String> listPath, Map<String, Object> instance) throws IOException {
        String text = Files.exists(file) ? Files.readString(file) : "";
        String updated;
        if (json()) {
            ObjectNode root = text.isBlank() ? JSON.createObjectNode() : (ObjectNode) JSON.readTree(text);
            ObjectNode parent = root;
            for (String key : listPath.subList(0, listPath.size() - 1)) {
                parent = parent.has(key) ? (ObjectNode) parent.get(key) : parent.putObject(key);
            }
            ArrayNode list = parent.has(listPath.get(listPath.size() - 1)) ? (ArrayNode) parent.get(listPath.get(listPath.size() - 1))
                    : parent.putArray(listPath.get(listPath.size() - 1));
            list.add(JSON.valueToTree(instance));
            updated = JSON.writeValueAsString(root) + "\n";
        } else {
            updated = addYamlItem(text, listPath, instance);
        }
        write(updated);
    }

    /** Removes the instance with this id; returns false when there is none. */
    public boolean removeInstance(List<String> listPath, String id) throws IOException {
        if (!Files.exists(file)) {
            return false;
        }
        String text = Files.readString(file);
        String updated;
        if (json()) {
            JsonNode root = JSON.readTree(text);
            JsonNode list = root;
            for (String key : listPath) {
                list = list.path(key);
            }
            if (!(list instanceof ArrayNode array)) {
                return false;
            }
            int index = indexOf(array, id);
            if (index < 0) {
                return false;
            }
            array.remove(index);
            updated = JSON.writeValueAsString(root) + "\n";
        } else {
            Optional<SequenceNode> sequence = sequence(text, listPath);
            if (sequence.isEmpty()) {
                return false;
            }
            List<Node> items = sequence.get().getValue();
            int index = -1;
            for (int i = 0; i < items.size(); i++) {
                if (id.equals(scalar(items.get(i), "id"))) {
                    index = i;
                }
            }
            if (index < 0) {
                return false;
            }
            List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
            int from = items.get(index).getStartMark().getLine();
            int to = index + 1 < items.size() ? items.get(index + 1).getStartMark().getLine() : endLine(items.get(index), lines);
            for (int line = to - 1; line >= from; line--) {
                lines.remove(line);
            }
            updated = String.join("\n", lines);
        }
        write(updated);
        return true;
    }

    /** Appends {@code NAME=value} lines to {@code .env} next to the config (owner-only). */
    public static void appendEnv(Path envFile, Map<String, String> values) throws IOException {
        StringBuilder text = new StringBuilder(Files.exists(envFile) ? Files.readString(envFile) : "");
        if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') {
            text.append('\n');
        }
        values.forEach((k, v) -> text.append(k).append('=').append(quoteEnv(v)).append('\n'));
        Files.writeString(envFile, text.toString(), StandardCharsets.UTF_8);
        ownerOnly(envFile);
    }

    // ---------------------------------------------------------------------- YAML text editing

    private static String addYamlItem(String text, List<String> listPath, Map<String, Object> instance) throws IOException {
        Optional<SequenceNode> sequence = text.isBlank() ? Optional.empty() : sequence(text, listPath);
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        if (sequence.isPresent() && !sequence.get().getValue().isEmpty()) {
            List<Node> items = sequence.get().getValue();
            Node last = items.get(items.size() - 1);
            Node first = items.get(0);
            int dashIndent = Math.max(0, first.getStartMark().getColumn() - 2);
            int insertAt = endLine(last, lines);
            lines.addAll(insertAt, block(instance, dashIndent));
            return String.join("\n", lines);
        }
        if (sequence.isPresent()) {
            throw new IOException("the instances list is empty or in flow style ([ … ]); add the instance by hand");
        }
        // no list yet: add the whole path at the end (only when none of it exists, to never duplicate a key)
        if (!text.isBlank() && topLevelKeys(text).contains(listPath.get(0))) {
            throw new IOException(String.join(".", listPath) + " can't be added automatically next to the existing "
                    + listPath.get(0) + " section; add the instance by hand");
        }
        StringBuilder out = new StringBuilder(text);
        if (!text.isEmpty() && !text.endsWith("\n")) {
            out.append('\n');
        }
        int indent = 0;
        for (String key : listPath) {
            out.append(" ".repeat(indent)).append(key).append(":\n");
            indent += 2;
        }
        block(instance, indent).forEach(line -> out.append(line).append('\n'));
        return out.toString();
    }

    /** The lines of a list item in block style, starting with {@code - } at {@code dashIndent}. */
    static List<String> block(Map<String, Object> value, int dashIndent) {
        List<String> lines = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            String prefix = first ? " ".repeat(dashIndent) + "- " : " ".repeat(dashIndent + 2);
            first = false;
            render(lines, prefix, entry.getKey(), entry.getValue(), dashIndent + 2);
        }
        return lines;
    }

    @SuppressWarnings("unchecked")
    private static void render(List<String> lines, String prefix, String key, Object value, int indent) {
        if (value instanceof Map<?, ?> map) {
            lines.add(prefix + key + ":");
            map.forEach((k, v) -> render(lines, " ".repeat(indent + 2), String.valueOf(k), v, indent + 2));
        } else if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?>) {
            // a list of objects: block items under the key
            lines.add(prefix + key + ":");
            for (Object item : list) {
                lines.addAll(block((Map<String, Object>) item, indent + 2));
            }
        } else if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            list.forEach(item -> items.add(scalarText(item)));
            lines.add(prefix + key + ": [" + String.join(", ", items) + "]");
        } else {
            lines.add(prefix + key + ": " + scalarText(value));
        }
    }

    /** A YAML scalar: references and anything special are quoted. */
    static String scalarText(Object value) {
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        String text = String.valueOf(value);
        if (text.matches("[A-Za-z0-9._/@:+-]+") && !text.matches("(?i)true|false|yes|no|on|off|null|~|[0-9.]+") && !text.contains(": ")) {
            return text;
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Optional<SequenceNode> sequence(String text, List<String> listPath) throws IOException {
        Node root;
        try {
            root = new Yaml(new LoaderOptions()).compose(new StringReader(text));
        } catch (RuntimeException e) {
            throw new IOException("the config file can't be parsed: " + e.getMessage().lines().findFirst().orElse(""));
        }
        Node current = root;
        for (String key : listPath) {
            if (!(current instanceof MappingNode mapping)) {
                return Optional.empty();
            }
            Node next = null;
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode k && k.getValue().equals(key)) {
                    next = tuple.getValueNode();
                }
            }
            if (next == null) {
                return Optional.empty();
            }
            current = next;
        }
        return current instanceof SequenceNode sequence ? Optional.of(sequence) : Optional.empty();
    }

    private static List<String> topLevelKeys(String text) throws IOException {
        List<String> keys = new ArrayList<>();
        Node root;
        try {
            root = new Yaml(new LoaderOptions()).compose(new StringReader(text));
        } catch (RuntimeException e) {
            throw new IOException("the config file can't be parsed");
        }
        if (root instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode k) {
                    keys.add(k.getValue());
                }
            }
        }
        return keys;
    }

    private static String scalar(Node node, String key) {
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode k && k.getValue().equals(key) && tuple.getValueNode() instanceof ScalarNode v) {
                    return v.getValue();
                }
            }
        }
        return null;
    }

    /** The line after an item: block items end where the next line starts; flow items end on their own line. */
    private static int endLine(Node item, List<String> lines) {
        int line = item.getEndMark().getLine();
        int column = item.getEndMark().getColumn();
        int end = column == 0 ? line : line + 1;
        // trailing blank lines and comments belong to what follows
        while (end > item.getStartMark().getLine() + 1 && end - 1 < lines.size() && lines.get(end - 1).isBlank()) {
            end--;
        }
        return Math.min(end, lines.size());
    }

    private static int indexOf(ArrayNode array, String id) {
        for (int i = 0; i < array.size(); i++) {
            if (id.equals(array.get(i).path("id").asText(null))) {
                return i;
            }
        }
        return -1;
    }

    private void write(String text) throws IOException {
        if (Files.exists(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.createDirectories(file.toAbsolutePath().getParent());
        }
        Files.writeString(file, text.endsWith("\n") ? text : text + "\n");
    }

    /** Restores the copy made by the last write (used when the edited config doesn't load). */
    public void restore() throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        if (Files.exists(backup)) {
            Files.move(backup, file, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(file);
        }
    }

    private static String quoteEnv(String value) {
        return value.matches("[A-Za-z0-9._/@:+=-]*") ? value : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void ownerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException e) {
            // not a POSIX file system
        }
    }

    /** A whole YAML document for a new config file, with a comment on top. */
    public static String document(String comment, Map<String, Object> content) {
        List<String> lines = new ArrayList<>();
        comment.lines().forEach(line -> lines.add("# " + line));
        content.forEach((key, value) -> render(lines, "", key, value, 0));
        return String.join("\n", lines) + "\n";
    }

    /** An ordered map, for building instance entries. */
    public static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }
}
