package ro.bitboy.mcpgp.runtime.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * How tool arguments appear in audit events ({@code audit.arguments}): {@code none}; {@code redacted},
 * the keys with every value masked; {@code hashed}, the SHA-256 of the canonical arguments.
 * File contents never appear: files are listed by name, size and SHA-256.
 */
public final class Arguments {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TextNode MASK = TextNode.valueOf("***");

    private Arguments() {
    }

    public static JsonNode render(Map<String, Object> arguments, String mode) {
        JsonNode args = JSON.valueToTree(arguments);
        return switch (mode) {
            case "none" -> TextNode.valueOf("none");
            case "hashed" -> JsonNodeFactory.instance.objectNode().put("sha256", sha256(canonical(args)));
            default -> mask(args);
        };
    }

    private static JsonNode mask(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode masked = JsonNodeFactory.instance.objectNode();
            object.fields().forEachRemaining(f -> masked.set(f.getKey(), f.getKey().equals("instance") ? f.getValue() : mask(f.getValue())));
            return masked;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode masked = JsonNodeFactory.instance.arrayNode();
            array.forEach(item -> masked.add(mask(item)));
            return masked;
        }
        return MASK;
    }

    /** Sorted keys, no whitespace: the same arguments always hash the same. */
    public static String canonical(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> keys = new ArrayList<>();
            object.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            StringBuilder out = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                out.append(i == 0 ? "" : ",").append(TextNode.valueOf(keys.get(i))).append(':').append(canonical(object.get(keys.get(i))));
            }
            return out.append('}').toString();
        }
        if (node instanceof ArrayNode array) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.size(); i++) {
                out.append(i == 0 ? "" : ",").append(canonical(array.get(i)));
            }
            return out.append(']').toString();
        }
        return node.toString();
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
