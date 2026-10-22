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
    private final ro.bitboy.mcpgp.runtime.config.ToolPolicy policy;
    private final ro.bitboy.mcpgp.runtime.call.Contracts contracts;

    InstancesList(Manifest manifest, RuntimeConfig config, ro.bitboy.mcpgp.runtime.call.Contracts contracts) {
        this.manifest = manifest;
        this.config = config;
        this.policy = new ro.bitboy.mcpgp.runtime.config.ToolPolicy(manifest, config);
        this.contracts = contracts;
    }

    @Override
    public Object call(McpSyncServerExchange exchange, JsonNode tool, Map<String, Object> arguments, CallRecord record) {
        String name = tool.path("name").asText();
        List<Map<String, Object>> instances = new ArrayList<>();
        for (Manifest.Api api : manifest.apis().values()) {
            boolean several = manifest.apis().size() > 1;
            if (several && !name.endsWith(api.toolPrefix() + "_instances_list")) {
                continue;
            }
            for (RuntimeConfig.Instance instance : config.instances().getOrDefault(api.id(), List.of())) {
                if (manifest.tools().values().stream().noneMatch(t -> t.path("kind").asText().equals("api")
                        && api.id().equals(t.path("api").asText()) && policy.allows(t, instance) && CallerAccess.allows(config, manifest, t, instance))) {
                    continue;
                }
                ro.bitboy.mcpgp.runtime.call.Contracts.Choice choice = contracts.of(instance);
                String contract = choice.contract();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", instance.id());
                if (several) {
                    entry.put("api", api.id());
                }
                if (contract != null) {
                    entry.put("contract", contract);
                }
                entry.put("contractSelection", instance.contract().equals("auto") ? "auto" : "pinned");
                if (instance.contract().equals("auto") && choice.selection().equals("detected")) {
                    entry.put("detectedVersion", choice.reported());
                }
                if (choice.reason() != null) {
                    entry.put("contractNote", choice.available() ? "default contract: " + choice.reason() : choice.reason());
                }
                entry.put("readOnly", instance.readOnly());
                entry.put("status", choice.available() ? "configured" : "unavailable");
                entry.put("tools", contract == null ? 0 : toolsOn(api.id(), contract, instance));
                instances.add(entry);
            }
        }
        return Map.of("instances", instances);
    }

    private long toolsOn(String api, String contract, RuntimeConfig.Instance instance) {
        return manifest.tools().values().stream()
                .filter(t -> api.equals(t.path("api").asText()))
                .filter(t -> t.path("variants").has(contract))
                .filter(t -> policy.allows(t, instance) && CallerAccess.allows(config, manifest, t, instance))
                .filter(t -> !instance.readOnly() || t.at("/annotations/readOnlyHint").asBoolean(false))
                .count();
    }
}
