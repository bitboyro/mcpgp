package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import java.time.Clock;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Bounded, expiring, immutable response snapshots. Never issues upstream requests. */
public final class RetainedResults {
    private record Entry(String owner, JsonNode value, long expires, int bytes, Map<String, Object> meta) { }
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Clock clock;
    private final String mode;
    private final int budget;
    private final long capacity;
    private final int ttl;
    private long used;

    public RetainedResults(JsonNode policies, Clock clock) {
        this.clock = clock;
        mode = policies.at("/results/mode").asText("inline");
        budget = policies.path("responseLimitKb").asInt(256) * 1024;
        capacity = policies.at("/results/maxStoredKb").asLong(65536) * 1024;
        ttl = policies.at("/results/ttlSeconds").asInt(900);
    }
    public boolean enabled() { return !mode.equals("inline"); }
    private static String owner(McpSyncServerExchange exchange) {
        return exchange == null ? "stdio" : String.valueOf(exchange.sessionId()) + ":" +
                (exchange.transportContext() == null ? "" : String.valueOf(exchange.transportContext().get(McpServerFactory.CLIENT_KEY)));
    }
    private void prune() {
        var it = entries.values().iterator();
        while (it.hasNext()) { Entry e = it.next(); if (e.expires <= clock.millis()) { used -= e.bytes; it.remove(); } }
    }
    public synchronized ToolOutput format(McpSyncServerExchange exchange, ToolOutput output) {
        if (!enabled()) return output;
        Object value = output.structured() != null ? output.structured() : Map.of("text", output.text() == null ? "" : output.text());
        JsonNode tree = Results.JSON.valueToTree(value);
        int bytes = Results.json(value).getBytes(StandardCharsets.UTF_8).length;
        if (mode.equals("auto") && bytes <= budget) return output;
        // Never label a response complete when the transport or mapper discarded bytes.
        if (output.meta() != null && output.meta().containsKey("mcpgp/truncated"))
            return new ToolOutput(Map.of("complete", false, "retained", false, "reason", "CAPTURE_LIMIT_EXCEEDED",
                    "message", "The operation completed, but its response exceeded the capture limit. Narrow reads; do not repeat writes to recover output."), null, output.meta());
        Map<String, Long> outcomes = new LinkedHashMap<>();
        if (tree.path("results").isArray()) tree.path("results").forEach(row -> outcomes.merge(row.path("state").asText("unknown"), 1L, Long::sum));
        prune();
        if (bytes > capacity || used + bytes > capacity) {
            return new ToolOutput(Map.of("retained", false, "complete", false, "reason", "RESULT_STORAGE_FULL", "outcomes", outcomes,
                    "message", "Execution finished, but its response could not be retained. Do not repeat writes to recover output."), null, output.meta());
        }
        String id = "r_" + UUID.randomUUID();
        Map<String, Object> meta = output.meta() == null ? Map.of() : Map.copyOf(output.meta());
        entries.put(id, new Entry(owner(exchange), tree, clock.millis() + ttl * 1000L, bytes, meta));
        used += bytes;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("storedBytes", bytes);
        if (!outcomes.isEmpty()) summary.put("outcomes", outcomes);
        JsonNode items = tree.isArray() ? tree : tree.path("items");
        if (items.isArray()) summary.put("returnedItems", items.size());
        if (tree.isObject()) { List<String> fields = new ArrayList<>(); tree.fieldNames().forEachRemaining(fields::add); summary.put("fields", fields.stream().limit(5).map(s -> s.length() > 40 ? s.substring(0, 40) + "…" : s).toList()); }
        return new ToolOutput(Map.of("resultId", id, "complete", true, "expiresInSeconds", ttl, "summary", summary), null, meta);
    }
    public synchronized Object get(McpSyncServerExchange exchange, Map<String, Object> args) {
        if (!Set.of("resultId", "pointer", "offset", "limit").containsAll(args.keySet())
                || !(args.get("resultId") instanceof String)
                || args.containsKey("pointer") && !(args.get("pointer") instanceof String))
            throw new ToolError("INPUT_VALIDATION", "Expected resultId, optional JSON pointer, offset and limit");
        for (String key : List.of("offset", "limit")) {
            if (args.containsKey(key) && (!(args.get(key) instanceof Number n) || n.doubleValue() != n.intValue()))
                throw new ToolError("INPUT_VALIDATION", key + " must be an integer");
        }
        prune();
        Entry entry = entries.get(String.valueOf(args.get("resultId")));
        if (entry == null || !entry.owner.equals(owner(exchange))) throw new ToolError("RESULT_NOT_FOUND", "Result unavailable or expired");
        JsonNode value;
        try { value = entry.value.at(String.valueOf(args.getOrDefault("pointer", ""))); }
        catch (IllegalArgumentException e) { throw new ToolError("INPUT_VALIDATION", "Invalid JSON pointer"); }
        if (value.isMissingNode()) throw new ToolError("INPUT_VALIDATION", "Pointer does not exist in the result");
        int offset = args.get("offset") instanceof Number n ? n.intValue() : 0;
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 25;
        if (offset < 0 || limit < 1 || limit > 200) throw new ToolError("INPUT_VALIDATION", "Invalid offset or limit");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultId", args.get("resultId")); result.put("complete", true); result.put("metadata", entry.meta);
        if (value.isArray() || value.isTextual()) {
            int total = value.isArray() ? value.size() : value.textValue().length();
            if (offset > total) throw new ToolError("INPUT_VALIDATION", "Offset exceeds result length");
            int next = offset;
            if (value.isArray()) {
                List<JsonNode> items = new ArrayList<>(); int size = 512;
                while (next < total && items.size() < limit) {
                    JsonNode item = value.get(next); int itemBytes = item.toString().getBytes(StandardCharsets.UTF_8).length;
                    if (size + itemBytes > budget) break;
                    items.add(item.deepCopy()); next++; size += itemBytes;
                }
                if (items.isEmpty() && next < total) throw new ToolError("RESULT_SLICE_TOO_LARGE", "One item exceeds the inline budget; use a pointer into that item");
                result.put("items", items);
            } else {
                String text = value.textValue(); next = Math.min(total, offset + Math.max(1, (budget - 512) / 6));
                if (next < total && next > offset && Character.isHighSurrogate(text.charAt(next - 1))) next--;
                result.put("text", text.substring(offset, next));
            }
            result.put("total", total); result.put("hasMore", next < total); if (next < total) result.put("nextOffset", next);
        } else {
            if (value.toString().getBytes(StandardCharsets.UTF_8).length > budget - 512)
                throw new ToolError("RESULT_SLICE_TOO_LARGE", "Select a smaller field with pointer", "Top-level fields: " + value.properties().stream().map(Map.Entry::getKey).limit(20).toList());
            result.put("value", value.deepCopy());
        }
        return result;
    }
}
