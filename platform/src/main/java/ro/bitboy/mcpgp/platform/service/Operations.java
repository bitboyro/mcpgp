package ro.bitboy.mcpgp.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.openapi.ApiModel;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition.ContractKey;
import ro.bitboy.mcpgp.core.tools.ToolPlan;
import ro.bitboy.mcpgp.core.tools.ToolPlanner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every operation of a version's contracts and what became of it (spec/platform-api.md §2.1): a tool,
 * a resource, left out by {@code select} (with the clause that did it), or hidden by a rule. The Tools
 * & rules page turns operations on and off from this.
 */
public final class Operations {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Operations() {
    }

    /** @param result a compile that succeeded */
    public static ObjectNode of(Compiler.Result result) {
        JsonNode document = result.definition().orElseThrow().document();
        Map<String, ToolPlan> plans = new LinkedHashMap<>();
        result.tools().forEach(p -> plans.put(p.api() + "|" + p.operationKey(), p));
        ObjectNode answer = NODES.objectNode();
        ArrayNode apis = answer.putArray("apis");
        for (JsonNode api : document.path("apis")) {
            String apiId = api.path("id").asText();
            JsonNode select = api.path("select");
            List<String> versions = new ArrayList<>();
            String defaultVersion = null;
            for (JsonNode c : api.path("contracts")) {
                String version = c.path("version").asText();
                if (result.contracts().containsKey(new ContractKey(apiId, version))) {
                    versions.add(version);
                    if (c.path("default").asBoolean(false)) {
                        defaultVersion = version;
                    }
                }
            }
            if (versions.isEmpty()) {
                continue;
            }
            if (defaultVersion == null) {
                defaultVersion = versions.get(versions.size() - 1);
            }
            List<String> order = new ArrayList<>(versions);
            order.remove(defaultVersion);
            order.add(0, defaultVersion);
            Map<String, Map<String, ApiModel.Operation>> byKey = new LinkedHashMap<>();
            for (String version : order) {
                for (ApiModel.Operation op : result.contracts().get(new ContractKey(apiId, version)).operations()) {
                    byKey.computeIfAbsent(op.key(), k -> new LinkedHashMap<>()).put(version, op);
                }
            }

            ObjectNode node = apis.addObject().put("id", apiId).put("title", api.path("title").asText(apiId));
            node.set("select", select.isMissingNode() ? NODES.objectNode() : select.deepCopy());
            ArrayNode operations = node.putArray("operations");
            for (Map.Entry<String, Map<String, ApiModel.Operation>> entry : byKey.entrySet()) {
                ApiModel.Operation op = entry.getValue().values().iterator().next();
                ObjectNode o = operations.addObject().put("key", entry.getKey()).put("method", op.method()).put("path", op.path());
                if (op.operationId() != null) {
                    o.put("operationId", op.operationId());
                }
                if (op.summary() != null) {
                    o.put("summary", op.summary());
                }
                o.put("deprecated", op.deprecated());
                ArrayNode tags = o.putArray("tags");
                op.tags().forEach(tags::add);
                ArrayNode in = o.putArray("contracts");
                entry.getValue().keySet().forEach(in::add);

                ToolPlan plan = plans.get(apiId + "|" + entry.getKey());
                if (plan != null) {
                    o.put("state", "resource".equals(plan.expose()) ? "resource" : "tool").put("tool", plan.name());
                    continue;
                }
                ToolPlanner.Exclusion exclusion = null;
                boolean selectedSomewhere = false;
                for (Map.Entry<String, ApiModel.Operation> variant : entry.getValue().entrySet()) {
                    ToolPlanner.Exclusion e = ToolPlanner.exclusion(select, apiId, variant.getValue(), variant.getKey());
                    if (e == null) {
                        selectedSomewhere = true;
                    } else if (exclusion == null) {
                        exclusion = e;
                    }
                }
                if (selectedSomewhere || exclusion == null) {
                    o.put("state", "hidden");
                    continue;
                }
                o.put("state", "excluded");
                ObjectNode why = o.putObject("exclusion").put("kind", exclusion.kind()).put("reason", exclusion.reason());
                if (exclusion.index() != null) {
                    why.put("index", exclusion.index());
                    why.set("selector", exclusion.selector());
                }
            }
        }
        return answer;
    }
}
