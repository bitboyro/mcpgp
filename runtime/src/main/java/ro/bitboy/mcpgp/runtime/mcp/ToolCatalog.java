package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The tools this server offers: manifest tool definitions with the {@code instance} enum filled in
 * from the runtime config (manifest.md §1.2), and which of them {@code tools/list} shows in the
 * build's exposure mode (tools-contract §15).
 */
public final class ToolCatalog {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final Manifest manifest;
    /** Every callable tool, by name, as served (instance enum filled). */
    private final Map<String, JsonNode> tools = new LinkedHashMap<>();

    public ToolCatalog(Manifest manifest, RuntimeConfig config) {
        this.manifest = manifest;
        Set<String> allIds = new LinkedHashSet<>();
        config.allInstances().forEach(i -> allIds.add(i.id()));
        for (JsonNode tool : manifest.tools().values()) {
            ObjectNode served = tool.deepCopy();
            JsonNode instance = served.at("/inputSchema/properties/instance");
            if (instance instanceof ObjectNode property && property.path("x-mcpgp-instanceEnum").asBoolean(false)) {
                ArrayNode ids = property.putArray("enum");
                String api = tool.path("api").asText(null);
                if (api != null) {
                    config.instances().getOrDefault(api, List.of()).forEach(i -> ids.add(i.id()));
                } else {
                    allIds.forEach(ids::add);
                }
                property.remove("x-mcpgp-instanceEnum");
            }
            tools.put(served.path("name").asText(), served);
        }
    }

    /** A tool by name, whether listed or reachable through discovery. */
    public Optional<JsonNode> tool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Map<String, JsonNode> all() {
        return tools;
    }

    /** The tools {@code tools/list} returns in this build's exposure mode. */
    public List<JsonNode> listed() {
        String mode = manifest.exposure().path("mode").asText("plain");
        Set<String> pinned = new LinkedHashSet<>();
        manifest.exposure().path("pinned").forEach(p -> pinned.add(p.asText()));
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode tool : tools.values()) {
            boolean api = tool.path("kind").asText().equals("api");
            boolean show = switch (mode) {
                case "hybrid" -> !api || pinned.contains(tool.path("name").asText());
                case "discovery", "toolsets" -> !api;
                default -> true;
            };
            if (show) {
                result.add(tool);
            }
        }
        return result;
    }

    /** The MCP form of a tool definition. */
    public static McpSchema.Tool toMcp(JsonNode tool) {
        JsonNode a = tool.path("annotations");
        McpSchema.ToolAnnotations annotations = new McpSchema.ToolAnnotations(null,
                a.path("readOnlyHint").asBoolean(false), a.path("destructiveHint").asBoolean(false),
                a.path("idempotentHint").asBoolean(false), a.path("openWorldHint").asBoolean(true), null);
        Map<String, Object> input = Results.JSON.convertValue(tool.path("inputSchema"), MAP);
        Map<String, Object> output = tool.path("outputSchema").isObject() ? Results.JSON.convertValue(tool.get("outputSchema"), MAP) : null;
        return new McpSchema.Tool(tool.path("name").asText(), tool.path("title").asText(null), tool.path("description").asText(""),
                input, output, annotations, null, null);
    }
}
