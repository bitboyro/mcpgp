package ro.bitboy.mcpgp.core.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;

/**
 * A manifest as an MCP client sees it: the {@code tools} of a {@code tools/list} result, in the build's
 * exposure mode, each with the fields MCP defines (name, title, description, inputSchema, outputSchema,
 * annotations). It is what the runtime serves, so a server can be audited without starting it.
 * Per-instance values are not known here: the {@code instance} input of a multi-instance build has no
 * {@code enum} (the customer's instances fill it in).
 */
public final class ToolsList {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ToolsList() {
    }

    /** {@code { "tools": [ … ] }}, as in the result of {@code tools/list}. */
    public static ObjectNode of(JsonNode manifest) {
        String mode = manifest.at("/exposure/mode").asText("plain");
        Set<String> pinned = new HashSet<>();
        manifest.at("/exposure/pinned").forEach(p -> pinned.add(p.asText()));
        ArrayNode tools = NODES.arrayNode();
        for (JsonNode tool : manifest.path("tools")) {
            boolean api = tool.path("kind").asText().equals("api");
            boolean listed = switch (mode) {
                case "hybrid" -> !api || pinned.contains(tool.path("x-mcpgp-original").asText(tool.path("name").asText()));
                case "discovery", "toolsets" -> !api;
                default -> true;
            };
            if (listed) {
                tools.add(mcp(tool));
            }
        }
        ObjectNode result = NODES.objectNode();
        result.set("tools", tools);
        return result;
    }

    /** The MCP form of one tool (the same fields the runtime sends). */
    static ObjectNode mcp(JsonNode tool) {
        ObjectNode out = NODES.objectNode();
        out.put("name", tool.path("name").asText());
        if (tool.hasNonNull("title")) {
            out.put("title", tool.path("title").asText());
        }
        out.put("description", tool.path("description").asText(""));
        ObjectNode input = tool.path("inputSchema").deepCopy();
        if (input.at("/properties/instance") instanceof ObjectNode instance) {
            instance.remove("x-mcpgp-instanceEnum");
        }
        out.set("inputSchema", input);
        if (tool.path("outputSchema").isObject()) {
            out.set("outputSchema", tool.get("outputSchema"));
        }
        JsonNode a = tool.path("annotations");
        ObjectNode annotations = out.putObject("annotations");
        annotations.put("readOnlyHint", a.path("readOnlyHint").asBoolean(false));
        annotations.put("destructiveHint", a.path("destructiveHint").asBoolean(false));
        annotations.put("idempotentHint", a.path("idempotentHint").asBoolean(false));
        annotations.put("openWorldHint", a.path("openWorldHint").asBoolean(true));
        return out;
    }
}
