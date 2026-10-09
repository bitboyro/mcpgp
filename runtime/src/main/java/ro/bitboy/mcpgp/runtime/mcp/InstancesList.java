package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code instances_list} (tools-contract §7): the configured instances with their contract, access and
 * the number of tools available on them. Never credentials or addresses' secrets.
 */
final class InstancesList implements ToolHandler {

    private final Manifest manifest;
    private final RuntimeConfig config;

    InstancesList(Manifest manifest, RuntimeConfig config) {
        this.manifest = manifest;
        this.config = config;
    }

    @Override
    public Object call(McpSyncServerExchange exchange, JsonNode tool, Map<String, Object> arguments) {
        String name = tool.path("name").asText();
        List<Map<String, Object>> instances = new ArrayList<>();
        for (Manifest.Api api : manifest.apis().values()) {
            boolean several = manifest.apis().size() > 1;
            if (several && !name.endsWith(api.toolPrefix() + "_instances_list")) {
                continue;
            }
            for (RuntimeConfig.Instance instance : config.instances().getOrDefault(api.id(), List.of())) {
                String contract = instance.contract().equals("auto") ? api.defaultContract() : instance.contract();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", instance.id());
                if (several) {
                    entry.put("api", api.id());
                }
                entry.put("contract", contract);
                entry.put("contractSelection", instance.contract().equals("auto") ? "auto" : "pinned");
                entry.put("readOnly", instance.readOnly());
                entry.put("status", "configured");
                entry.put("tools", toolsOn(api.id(), contract, instance.readOnly()));
                instances.add(entry);
            }
        }
        return Map.of("instances", instances);
    }

    private long toolsOn(String api, String contract, boolean readOnly) {
        return manifest.tools().values().stream()
                .filter(t -> api.equals(t.path("api").asText()))
                .filter(t -> t.path("variants").has(contract))
                .filter(t -> !readOnly || t.at("/annotations/readOnlyHint").asBoolean(false))
                .count();
    }
}
