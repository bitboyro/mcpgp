package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

/**
 * Caps on what an agent sends (definition {@code safety.inputLimits}, on by default): the size of a
 * tool call's arguments, and the length of each text value whose schema sets no {@code maxLength}.
 * Inline file contents (base64) don't count: {@code files.base64.maxKb} limits them.
 */
public final class InputLimits {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean enabled;
    private final int requestBytes;
    private final int stringMax;
    private final int base64Bytes;

    InputLimits(boolean enabled, int requestKb, int stringMax, int base64Kb) {
        this.enabled = enabled;
        this.requestBytes = requestKb * 1024;
        this.stringMax = stringMax;
        this.base64Bytes = base64Kb * 1024;
    }

    public static InputLimits of(Manifest manifest, RuntimeConfig config) {
        JsonNode limits = manifest.policies().path("inputLimits");
        return new InputLimits(limits.path("enabled").asBoolean(true), limits.path("requestKb").asInt(1024),
                limits.path("stringMaxLength").asInt(65536), config.files().base64Enabled() ? config.files().base64MaxKb() : 0);
    }

    /** The largest HTTP request body: the arguments' limit, inline files in base64, and room for the JSON-RPC envelope. */
    public long maxRequestBytes() {
        return enabled ? requestBytes + (long) Math.ceil(base64Bytes * 4 / 3.0) + 64 * 1024 : Long.MAX_VALUE;
    }

    public boolean enabled() {
        return enabled;
    }

    void check(JsonNode tool, Map<String, Object> arguments) {
        if (!enabled) {
            return;
        }
        JsonNode args = JSON.valueToTree(arguments);
        JsonNode counted = withoutFiles(args.deepCopy(), tool.path("inputSchema"));
        int size = counted.toString().getBytes(StandardCharsets.UTF_8).length;
        if (size > requestBytes) {
            throw new ToolError("INPUT_TOO_LARGE", "The arguments are " + (size / 1024) + " KB; the limit is " + (requestBytes / 1024) + " KB",
                    "Send less at once: split the work into several calls, or send a file instead of inline text if the tool takes one");
        }
        longText(args, tool.path("inputSchema"), "");
    }

    private void longText(JsonNode value, JsonNode schema, String at) {
        if (value.isTextual()) {
            if (!schema.has("maxLength") && !schema.has("contentEncoding") && value.asText().length() > stringMax) {
                throw new ToolError("INPUT_TOO_LARGE", (at.isEmpty() ? "a value" : at) + " is " + value.asText().length()
                        + " characters; the limit is " + stringMax, "Send a shorter text, or split it over several calls");
            }
            return;
        }
        if (value.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                if (field.getValue().isTextual() && field.getKey().equals("base64")) {
                    continue; // a file's contents, under files.base64.maxKb (the schema may refer to its file object elsewhere)
                }
                longText(field.getValue(), property(schema, field.getKey()), at.isEmpty() ? field.getKey() : at + "." + field.getKey());
            }
        } else if (value.isArray()) {
            JsonNode items = schema.path("items");
            for (int i = 0; i < value.size(); i++) {
                longText(value.get(i), items, at + "[" + i + "]");
            }
        }
    }

    /** Inline file contents ({@code contentEncoding: base64}) replaced by nothing, for counting. */
    private static JsonNode withoutFiles(JsonNode value, JsonNode schema) {
        if (value instanceof ObjectNode object) {
            for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                JsonNode property = property(schema, field.getKey());
                if (field.getValue().isTextual() && (property.has("contentEncoding") || field.getKey().equals("base64"))) {
                    field.setValue(JSON.getNodeFactory().textNode(""));
                } else {
                    withoutFiles(field.getValue(), property);
                }
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) {
                withoutFiles(item, schema.path("items"));
            }
        }
        return value;
    }

    /** A property's schema, also inside oneOf / anyOf branches (file objects are a oneOf of forms). */
    private static JsonNode property(JsonNode schema, String name) {
        JsonNode direct = schema.path("properties").path(name);
        if (!direct.isMissingNode()) {
            return direct;
        }
        for (String key : new String[] {"oneOf", "anyOf", "allOf"}) {
            for (JsonNode branch : schema.path(key)) {
                JsonNode found = branch.path("properties").path(name);
                if (!found.isMissingNode()) {
                    return found;
                }
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
