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
            if (served.path("kind").asText().equals("api") && !manifest.policies().at("/results/mode").asText("inline").equals("inline")
                    && served.path("outputSchema").isObject()) {
                JsonNode original = served.path("outputSchema");
                ObjectNode union = served.putObject("outputSchema");
                union.put("type", "object");
                if (original.has("$defs")) union.set("$defs", original.path("$defs"));
                union.putArray("anyOf").add(original).addObject().put("type", "object").putArray("anyOf")
                        .addObject().putArray("required").add("resultId");
                ((ObjectNode) union.path("anyOf").get(1)).withArray("anyOf").addObject().putArray("required").add("retained").add("complete");
            }
            tools.put(served.path("name").asText(), served);
        }
        hideUnsatisfied(config);
    }

    /**
     * Tools no instance's credentials can call (auth.md §2.2), with {@code unsatisfiedTools: hide} (the
     * default): not listed, not callable. A tool one instance can call stays, and calls for the others
     * answer AUTH_DOWNSTREAM saying what's missing; with {@code error}, every tool stays.
     */
    private void hideUnsatisfied(RuntimeConfig config) {
        Map<String, List<String>> hidden = new java.util.TreeMap<>();
        for (Map.Entry<String, JsonNode> e : List.copyOf(tools.entrySet())) {
            JsonNode tool = e.getValue();
            Manifest.Api api = tool.path("kind").asText().equals("api") ? manifest.apis().get(tool.path("api").asText()) : null;
            if (api == null || !api.node().path("downstreamAuth").path("unsatisfiedTools").asText("hide").equals("hide")) {
                continue;
            }
            JsonNode kinds = api.node().path("downstreamAuth").path("schemes");
            List<String> reasons = new ArrayList<>();
            boolean usable = false;
            for (RuntimeConfig.Instance instance : config.instances().getOrDefault(api.id(), List.of())) {
                // an auto instance may run any shipped contract (detection decides): a tool usable in one of them stays
                for (String contract : instance.contract().equals("auto") ? api.contracts().keySet() : List.of(instance.contract())) {
                    JsonNode variant = tool.path("variants").get(contract);
                    if (variant == null) {
                        continue;
                    }
                    JsonNode security = variant.get("security");
                    String unmet = security != null && security.isArray() && security.isEmpty() ? null // a public operation
                            : ro.bitboy.mcpgp.runtime.call.Satisfaction.unmet(instance, security, kinds);
                    if (unmet == null) {
                        usable = true;
                        break;
                    }
                    reasons.add(instance.id() + " (" + instance.authType() + "): needs " + unmet);
                }
                if (usable) {
                    break;
                }
            }
            if (!usable && !reasons.isEmpty()) {
                tools.remove(e.getKey());
                hidden.put(e.getKey(), reasons);
            }
        }
        if (!hidden.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(ToolCatalog.class).warn("{} tool(s) hidden: no instance's credentials can call them"
                    + " (downstreamAuth.unsatisfiedTools: hide): {}", hidden.size(), hidden);
            this.hidden.putAll(hidden);
        }
    }

    /** Tools hidden because no instance's credentials can call them, with why (for doctor and the logs). */
    private final Map<String, List<String>> hidden = new java.util.TreeMap<>();

    public Map<String, List<String>> hidden() {
        return hidden;
    }

    /** A tool by name, whether listed or reachable through discovery. */
    public Optional<JsonNode> tool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** Adds a tool (a namespaced instance's copy). */
    void add(JsonNode tool) {
        tools.put(tool.path("name").asText(), tool);
    }

    /** The tools made from {@code name} for each instance (namespaced), or the tool itself. */
    public List<JsonNode> servedAs(String name) {
        JsonNode own = tools.get(name);
        if (own != null) {
            return List.of(own);
        }
        return tools.values().stream().filter(t -> name.equals(t.path("x-mcpgp-original").asText(null))).toList();
    }

    private static final java.util.regex.Pattern ONLY = java.util.regex.Pattern.compile(" ?\\([0-9][^)]* only\\)");

    /**
     * An API tool for one instance (mixedContracts: namespaced): named {@code <instance>__<tool>}, without
     * the {@code instance} input, with only the fields of {@code contract} and no "(2.0 only)" marks.
     */
    static ObjectNode forInstance(JsonNode tool, String instance, String contract) {
        ObjectNode copy = tool.deepCopy();
        String name = tool.path("name").asText();
        copy.put("name", instance.toLowerCase(java.util.Locale.ROOT).replace('-', '_') + "__" + name);
        copy.put("x-mcpgp-original", name);
        copy.put("x-mcpgp-instance", instance);
        copy.put("description", "On " + instance + " (contract " + contract + "). " + tool.path("description").asText(""));
        ObjectNode schema = (ObjectNode) copy.path("inputSchema");
        if (schema.path("properties") instanceof ObjectNode properties) {
            properties.remove("instance");
            for (java.util.Iterator<Map.Entry<String, JsonNode>> groups = properties.fields(); groups.hasNext(); ) {
                if (groups.next().getValue() instanceof ObjectNode group) {
                    onlyContract(group, contract);
                }
            }
        }
        dropRequired(schema, "instance");
        return copy;
    }

    private static void onlyContract(ObjectNode group, String contract) {
        if (!(group.path("properties") instanceof ObjectNode members)) {
            return;
        }
        for (String field : List.copyOf(iterable(members.fieldNames()))) {
            JsonNode member = members.get(field);
            JsonNode marks = member.path("x-mcpgp-contracts");
            if (marks.isArray()) {
                boolean has = false;
                for (JsonNode mark : marks) {
                    has |= mark.asText().equals(contract);
                }
                if (!has) {
                    members.remove(field);
                    dropRequired(group, field);
                    continue;
                }
                ((ObjectNode) member).remove("x-mcpgp-contracts");
                if (member.path("description").isTextual()) {
                    ((ObjectNode) member).put("description", ONLY.matcher(member.path("description").asText()).replaceAll(""));
                }
            }
        }
    }

    private static void dropRequired(ObjectNode schema, String field) {
        if (schema.path("required") instanceof ArrayNode required) {
            for (int i = required.size() - 1; i >= 0; i--) {
                if (required.get(i).asText().equals(field)) {
                    required.remove(i);
                }
            }
        }
    }

    private static <T> List<T> iterable(java.util.Iterator<T> it) {
        List<T> list = new ArrayList<>();
        it.forEachRemaining(list::add);
        return list;
    }

    /** Removes tools this runtime can't run, so they are neither listed nor callable. */
    void remove(java.util.Collection<String> names) {
        names.forEach(tools::remove);
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
                case "hybrid" -> !api || pinned.contains(tool.path("x-mcpgp-original").asText(tool.path("name").asText()));
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
