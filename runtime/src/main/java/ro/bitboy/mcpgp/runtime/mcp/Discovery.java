package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Discovery tools (tools-contract §15): {@code search_tools} / {@code describe_tool} / {@code call_tool}
 * and {@code list_operations} / {@code get_operation_schema} / {@code invoke_operation}. Searching ranks
 * with BM25 over name, title, tags and description, exact name matches first. Calls go through the
 * same path as direct calls: validation, confirmation, auth and audit are the same.
 */
final class Discovery {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PAGE = 25;
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final Manifest manifest;
    private final RuntimeConfig config;
    private final ToolCatalog catalog;
    private final ro.bitboy.mcpgp.runtime.call.Contracts contracts;
    /** Runs a tool call exactly like a direct one. */
    private final BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> dispatch;
    private final List<Doc> docs = new ArrayList<>();
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private double averageLength;

    private record Doc(JsonNode tool, Map<String, Integer> terms, int length) {
    }

    Discovery(Manifest manifest, RuntimeConfig config, ToolCatalog catalog, ro.bitboy.mcpgp.runtime.call.Contracts contracts,
              BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> dispatch) {
        this.manifest = manifest;
        this.config = config;
        this.catalog = catalog;
        this.contracts = contracts;
        this.dispatch = dispatch;
        for (JsonNode tool : catalog.all().values()) {
            if (!tool.path("kind").asText().equals("api")) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            text.append(tool.path("name").asText().replace('_', ' ')).append(' ')
                    .append(tool.path("title").asText()).append(' ').append(tool.path("description").asText());
            tool.at("/operation/tags").forEach(t -> text.append(' ').append(t.asText()));
            Map<String, Integer> terms = new HashMap<>();
            List<String> words = words(text.toString());
            words.forEach(w -> terms.merge(w, 1, Integer::sum));
            terms.keySet().forEach(t -> documentFrequency.merge(t, 1, Integer::sum));
            docs.add(new Doc(tool, terms, words.size()));
        }
        averageLength = docs.stream().mapToInt(Doc::length).average().orElse(1);
    }

    Map<String, ToolHandler> handlers() {
        Map<String, ToolHandler> handlers = new LinkedHashMap<>();
        handlers.put("search_tools", (exchange, tool, args, record) -> search(args));
        handlers.put("describe_tool", (exchange, tool, args, record) -> describe(args));
        handlers.put("call_tool", (exchange, tool, args, record) -> invoke(exchange, args));
        handlers.put("list_operations", (exchange, tool, args, record) -> listOperations(args));
        handlers.put("get_operation_schema", (exchange, tool, args, record) -> operationSchema(args));
        handlers.put("invoke_operation", (exchange, tool, args, record) -> invoke(exchange, args));
        return handlers;
    }

    private Object search(Map<String, Object> args) {
        String query = String.valueOf(args.getOrDefault("query", ""));
        int limit = args.get("limit") instanceof Number n ? Math.max(1, Math.min(50, n.intValue())) : 10;
        // in multi-API servers an id may name an instance of several APIs: each tool is matched against its own API's
        Map<String, String> contracts = args.get("instance") == null ? null : contractsOf(args.get("instance").toString());
        List<String> terms = words(query);
        String exact = query.strip().toLowerCase(Locale.ROOT);
        record Hit(JsonNode tool, double score) {
        }
        List<Hit> hits = new ArrayList<>();
        for (Doc doc : docs) {
            if (contracts != null) {
                String contract = contracts.get(doc.tool().path("api").asText());
                if (contract == null || !doc.tool().path("variants").has(contract)) {
                    continue;
                }
            }
            double score = doc.tool().path("name").asText().equals(exact) ? 1_000 : 0;
            for (String term : terms) {
                int tf = doc.terms().getOrDefault(term, 0);
                if (tf == 0) {
                    continue;
                }
                double idf = Math.log(1 + (docs.size() - documentFrequency.get(term) + 0.5) / (documentFrequency.get(term) + 0.5));
                score += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * doc.length() / averageLength));
            }
            if (score > 0) {
                hits.add(new Hit(doc.tool(), score));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(h -> h.tool().path("name").asText()));
        List<Map<String, Object>> results = new ArrayList<>();
        for (Hit hit : hits.subList(0, Math.min(limit, hits.size()))) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("name", hit.tool().path("name").asText());
            line.put("title", hit.tool().path("title").asText(""));
            line.put("summary", summary(hit.tool().path("description").asText("")));
            line.put("readOnly", hit.tool().at("/annotations/readOnlyHint").asBoolean(false));
            results.add(line);
        }
        return Map.of("tools", results);
    }

    private Object describe(Map<String, Object> args) {
        JsonNode tool = apiTool(args.get("name"));
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", tool.path("name").asText());
        definition.put("title", tool.path("title").asText(""));
        definition.put("description", tool.path("description").asText(""));
        definition.put("inputSchema", JSON.convertValue(tool.path("inputSchema"), Map.class));
        definition.put("annotations", JSON.convertValue(tool.path("annotations"), Map.class));
        return definition;
    }

    private Object invoke(McpSyncServerExchange exchange, Map<String, Object> args) {
        JsonNode tool = apiTool(args.get("name"));
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (args.get("arguments") instanceof Map<?, ?> given) {
            given.forEach((k, v) -> arguments.put(String.valueOf(k), v));
        }
        if (args.containsKey("instance") && !arguments.containsKey("instance")) {
            arguments.put("instance", args.get("instance"));
        }
        McpSchema.CallToolResult result = dispatch.apply(exchange, new McpSchema.CallToolRequest(tool.path("name").asText(), arguments, null));
        return new Passthrough(result);
    }

    private Object listOperations(Map<String, Object> args) {
        String tag = args.get("tag") == null ? null : args.get("tag").toString();
        String query = args.get("query") == null ? null : args.get("query").toString().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> all = new ArrayList<>();
        for (Doc doc : docs) {
            JsonNode tool = doc.tool();
            if (tag != null && !contains(tool.at("/operation/tags"), tag)) {
                continue;
            }
            if (query != null && !(tool.path("name").asText() + " " + tool.path("title").asText() + " " + tool.path("description").asText())
                    .toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            Manifest.Api api = manifest.apis().get(tool.path("api").asText());
            JsonNode variant = tool.path("variants").has(api.defaultContract()) ? tool.path("variants").get(api.defaultContract())
                    : tool.path("variants").elements().next();
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("name", tool.path("name").asText());
            line.put("method", variant.path("method").asText());
            line.put("path", variant.path("path").asText());
            line.put("summary", summary(tool.path("title").asText(tool.path("description").asText(""))));
            all.add(line);
        }
        return Paging.page(all, args.get("cursor"), PAGE);
    }

    private Object operationSchema(Map<String, Object> args) {
        JsonNode tool = apiTool(args.get("name"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", tool.path("name").asText());
        result.put("inputSchema", JSON.convertValue(tool.path("inputSchema"), Map.class));
        List<Map<String, Object>> responses = new ArrayList<>();
        Manifest.Api api = manifest.apis().get(tool.path("api").asText());
        JsonNode variant = tool.path("variants").path(api.defaultContract());
        for (JsonNode r : variant.path("responses")) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("status", r.path("status").asText());
            if (r.has("contentType")) {
                line.put("contentType", r.path("contentType").asText());
            }
            responses.add(line);
        }
        result.put("responses", responses);
        return result;
    }

    /** An API tool by name; discovery never reaches built-in or pack tools. */
    private JsonNode apiTool(Object name) {
        JsonNode tool = name == null ? null : catalog.tool(name.toString()).orElse(null);
        if (tool == null || !tool.path("kind").asText().equals("api")) {
            throw new ToolError("INPUT_VALIDATION", "No tool named '" + name + "'", "Use search_tools or list_operations to find one");
        }
        return tool;
    }

    /** The contract each API's instance of this id uses (APIs without one are absent). */
    private Map<String, String> contractsOf(String instance) {
        Map<String, String> contracts = new LinkedHashMap<>();
        for (RuntimeConfig.Instance i : config.allInstances()) {
            if (i.id().equals(instance)) {
                String contract = this.contracts.of(i).contract();
                contracts.put(i.api(), contract != null ? contract : manifest.apis().get(i.api()).defaultContract());
            }
        }
        return contracts;
    }

    static String summary(String description) {
        String text = description.replaceAll("\\s+", " ").strip();
        int end = text.indexOf(". ");
        String first = end > 0 ? text.substring(0, end + 1) : text;
        return first.length() <= 140 ? first : first.substring(0, 139) + "…";
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() > 1) {
                words.add(w.length() > 4 && w.endsWith("s") ? w.substring(0, w.length() - 1) : w);
            }
        }
        return words;
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (item.asText().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /** The inner tool's result, returned as is. */
    record Passthrough(McpSchema.CallToolResult result) {
    }
}
