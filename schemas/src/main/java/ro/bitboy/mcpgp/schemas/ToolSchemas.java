package ro.bitboy.mcpgp.schemas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;

/** Deterministic client schema transformations shared by runtime and build-time previews. */
public final class ToolSchemas {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private ToolSchemas() {}

    public static ObjectNode served(JsonNode tool, JsonNode policies) {
        ObjectNode served = tool.deepCopy();
        if (served.path("kind").asText().equals("api") && !policies.at("/results/mode").asText("inline").equals("inline")
                && served.path("outputSchema").isObject()) {
            JsonNode original = served.path("outputSchema");
            ObjectNode union = served.putObject("outputSchema");
            union.put("type", "object");
            if (original.has("$defs")) union.set("$defs", original.path("$defs"));
            union.putArray("anyOf").add(original).addObject().put("type", "object").putArray("anyOf")
                    .addObject().putArray("required").add("resultId");
            ((ObjectNode) union.path("anyOf").get(1)).withArray("anyOf").addObject().putArray("required").add("retained").add("complete");
        }
        return served;
    }

    public static ObjectNode sessionPolicy() {
        ObjectNode tool = NODES.objectNode().put("name", "session_policy").put("kind", "builtin").put("handler", "session_policy")
                .put("description", "Inspect this connection's restrictions or add restrictions. Restrict can only narrow access until the session ends.");
        ObjectNode input = tool.putObject("inputSchema").put("type", "object").put("additionalProperties", false);
        ObjectNode properties = input.putObject("properties"); properties.putObject("action").put("type", "string").putArray("enum").add("get").add("restrict");
        properties.putObject("tools").put("type", "object").putObject("properties").setAll((ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of("allow", Map.of("type", "array", "items", Map.of("type", "string")), "deny", Map.of("type", "array", "items", Map.of("type", "string")), "readOnly", Map.of("type", "boolean"))));
        properties.putObject("instances").put("type", "array").putObject("items").put("type", "string");
        return tool;
    }
}
