package ro.bitboy.mcpgp.core.manifest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Deterministic JSON (manifest.md §9): object keys sorted by UTF-16 code units (as RFC 8785), arrays
 * kept in order. {@link #compact} is the form digests are computed over; {@link #pretty} is the same
 * content, indented by two spaces with a final newline, for files people review and diff.
 */
public final class CanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private CanonicalJson() {
    }

    /** A copy with every object's keys sorted. */
    public static JsonNode sorted(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            ObjectNode result = NODES.objectNode();
            names.forEach(name -> result.set(name, sorted(object.get(name))));
            return result;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode result = NODES.arrayNode();
            array.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return node;
    }

    public static String compact(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(sorted(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pretty(JsonNode node) {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"))
                .withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER));
        try {
            return MAPPER.writer(printer).writeValueAsString(sorted(node)) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code sha256:<hex>} of the bytes. */
    public static String digest(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String digest(String text) {
        return digest(text.getBytes(StandardCharsets.UTF_8));
    }
}
