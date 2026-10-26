package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.config.ToolPolicy;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Captured caller restrictions, shared by helpers, catalogs and final dispatch. */
public final class CallerAccess {
    public static final String SCOPES = "mcpgp.scopes";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final Map<Manifest, Sessions> SHARED = new ConcurrentHashMap<>();
    private record Context(String principal, Set<String> scopes, String session, List<JsonNode> restrictions) { }
    public static final class Binding implements AutoCloseable {
        private final Context old;
        private Binding(Context context) { old = CURRENT.get(); CURRENT.set(context); }
        public void close() { if (old == null) { CURRENT.remove(); } else { CURRENT.set(old); } }
    }
    private static final class Sessions {
        final Map<String, List<JsonNode>> policies = new java.util.LinkedHashMap<>();
        volatile Consumer<String> changed = session -> { };
    }
    private CallerAccess() { }
    private static Sessions sessions(Manifest manifest) { return SHARED.computeIfAbsent(manifest, k -> new Sessions()); }
    private static String key(String principal, String session) { return principal + "\0" + (session == null ? "stdio" : session); }
    public static Binding bind(String principal, Set<String> scopes, String session, Manifest manifest) {
        Sessions sessions = sessions(manifest);
        List<JsonNode> restrictions;
        synchronized (sessions) { restrictions = sessions.policies.getOrDefault(key(principal, session), List.of()); }
        return new Binding(new Context(principal, Set.copyOf(scopes), session, restrictions));
    }
    public static Binding enter(McpSyncServerExchange exchange, RuntimeConfig config, Manifest manifest) {
        if (CURRENT.get() != null) { return new Binding(CURRENT.get()); }
        Object client = exchange == null || exchange.transportContext() == null ? null : exchange.transportContext().get(McpServerFactory.CLIENT_KEY);
        Object scopes = exchange == null || exchange.transportContext() == null ? null : exchange.transportContext().get(SCOPES);
        Set<String> granted = new java.util.LinkedHashSet<>();
        if (scopes instanceof Set<?> values) { values.forEach(v -> granted.add(v.toString())); }
        String principal = config.server().transport().equals("stdio") ? "stdio" : client == null ? "anonymous" : config.server().authMode() + ":" + client;
        return bind(principal, granted, config.server().transport().equals("stdio") || exchange == null ? null : exchange.sessionId(), manifest);
    }
    private static Context context(RuntimeConfig config, Manifest manifest) {
        Context context = CURRENT.get();
        if (context != null || !config.server().transport().equals("stdio")) { return context; }
        Sessions sessions = sessions(manifest);
        synchronized (sessions) { return new Context("stdio", Set.of(), null, sessions.policies.getOrDefault(key("stdio", null), List.of())); }
    }
    private static boolean instance(JsonNode selection, RuntimeConfig.Instance instance) {
        if (!selection.has("instances") || instance == null) { return true; }
        String name = instance.api() + "/" + instance.id();
        for (JsonNode match : selection.path("instances")) {
            if (glob(match.asText(), name)) { return true; }
        }
        return false;
    }
    private static boolean selection(JsonNode selection, JsonNode tool, RuntimeConfig.Instance instance) {
        return instance(selection, instance) && ToolPolicy.permits(selection.path("tools"), tool, tool.path("name").asText());
    }
    public static boolean glob(String pattern, String name) {
        String regex = java.util.Arrays.stream(pattern.split("\\*", -1)).map(java.util.regex.Pattern::quote).collect(java.util.stream.Collectors.joining(".*"));
        return name.matches(regex);
    }
    public static boolean allows(RuntimeConfig config, Manifest manifest, JsonNode tool, RuntimeConfig.Instance instance) {
        Context context = context(config, manifest);
        if (context == null || tool.path("kind").asText().equals("builtin")) { return true; }
        JsonNode identities = config.document().path("_identities");
        if (!selection(identities.path("default"), tool, instance) || !selection(identities.path("subjects").path(context.principal()), tool, instance)) { return false; }
        // scope grants apply to OAuth callers, the only ones whose credential carries scopes; static keys, anonymous
        // and stdio callers are governed by the default and their subject (policy-sources-and-sessions.md)
        JsonNode scopes = identities.path("scopes");
        if (context.principal().startsWith("oauth:") && !scopes.isEmpty()) {
            boolean grant = false;
            for (String scope : context.scopes()) {
                JsonNode restriction = scopes.path(scope);
                for (JsonNode deny : restriction.path("tools").path("deny")) {
                    if (glob(deny.asText(), tool.path("name").asText())) { return false; }
                }
                if (!restriction.isMissingNode() && selection(restriction, tool, instance)) { grant = true; }
            }
            if (!grant) { return false; }
        }
        if (context.principal().startsWith("oauth:") && !new ro.bitboy.mcpgp.runtime.http.ScopePolicy(config.server().auth().path("scopes")).allows(tool, context.scopes())) { return false; }
        return context.restrictions().stream().allMatch(p -> selection(p, tool, instance));
    }
    public static boolean visible(RuntimeConfig config, Manifest manifest, JsonNode tool) {
        if (!tool.has("api")) { return allows(config, manifest, tool, null); }
        return config.instances().getOrDefault(tool.path("api").asText(), List.of()).stream().anyMatch(i -> allows(config, manifest, tool, i));
    }
    public static void require(RuntimeConfig config, Manifest manifest, JsonNode tool, RuntimeConfig.Instance instance) {
        if (!allows(config, manifest, tool, instance)) { throw new ToolError("NOT_AVAILABLE_ON_INSTANCE", "Caller or session policy blocks this operation"); }
    }
    public static void onChanged(Manifest manifest, Consumer<String> listener) { sessions(manifest).changed = listener; }
    public static void forget(Manifest manifest, String principal, String session) {
        Sessions sessions = sessions(manifest);
        synchronized (sessions) { sessions.policies.remove(key(principal, session)); }
    }
    public static void clear(Manifest manifest) { SHARED.remove(manifest); }
    public static Object change(Manifest manifest, RuntimeConfig config, Map<String, Object> args) {
        Context context = context(config, manifest);
        if (context == null) { throw new ToolError("INPUT_VALIDATION", "A caller session is required"); }
        JsonNode request = JSON.valueToTree(args);
        request.fieldNames().forEachRemaining(f -> { if (!Set.of("action", "tools", "instances").contains(f)) { throw new ToolError("INPUT_VALIDATION", "Unknown session policy field"); } });
        String action = request.path("action").asText("get");
        Sessions sessions = sessions(manifest);
        String key = key(context.principal(), context.session());
        synchronized (sessions) {
            if (action.equals("restrict")) {
                ObjectNode restriction = JSON.createObjectNode();
                for (String field : List.of("tools", "instances")) { if (request.has(field)) { restriction.set(field, request.get(field).deepCopy()); } }
                validateRestriction(restriction, manifest, config);
                if (!sessions.policies.containsKey(key) && sessions.policies.size() >= 10000) { throw new ToolError("RATE_LIMITED", "Session policy capacity reached"); }
                List<JsonNode> previous = sessions.policies.getOrDefault(key, List.of());
                if (previous.size() >= 64) { throw new ToolError("RATE_LIMITED", "This session has reached its restriction limit"); }
                List<JsonNode> next = new ArrayList<>(previous); next.add(restriction); sessions.policies.put(key, List.copyOf(next));
                sessions.changed.accept(context.session());
            } else if (!action.equals("get")) { throw new ToolError("INPUT_VALIDATION", "action is get or restrict; restrictions cannot be widened"); }
            return Map.of("restrictions", sessions.policies.getOrDefault(key, List.of()), "version", sessions.policies.getOrDefault(key, List.of()).size());
        }
    }
    public static void validateRestriction(JsonNode restriction, Manifest manifest, RuntimeConfig config) {
        // Same schema as identity restrictions; reject references to files/env and unknown policy fields.
        ObjectNode document = JSON.createObjectNode().put("version", 1); document.set("default", restriction);
        if (!new ro.bitboy.mcpgp.schemas.Schemas().problems(ro.bitboy.mcpgp.schemas.SchemaKind.IDENTITY_POLICY, document).isEmpty()
                || restriction.toString().contains("${")) { throw new ToolError("INPUT_VALIDATION", "Invalid caller restriction"); }
        java.util.Set<String> names = new java.util.HashSet<>();
        ToolPolicy policy = new ToolPolicy(manifest, config);
        var tools = new ArrayList<JsonNode>(manifest.tools().values()); manifest.root().path("resources").path("items").forEach(tools::add);
        tools.forEach(t -> { if (t.has("api")) { config.instances().getOrDefault(t.path("api").asText(), List.of()).forEach(i -> names.add(policy.name(t, i))); } else { names.add(t.path("name").asText()); } });
        for (String field : List.of("allow", "deny")) { for (JsonNode name : restriction.path("tools").path(field)) { if (!name.asText().contains("*") && !names.contains(name.asText())) { throw new ToolError("INPUT_VALIDATION", "Unknown tool in caller restriction"); } } }
        for (JsonNode name : restriction.path("instances")) { if (!name.asText().contains("*") && config.allInstances().stream().noneMatch(i -> (i.api() + "/" + i.id()).equals(name.asText()))) { throw new ToolError("INPUT_VALIDATION", "Unknown instance in caller restriction"); } }
    }
    public static ObjectNode tool() {
        return ro.bitboy.mcpgp.schemas.ToolSchemas.sessionPolicy();
    }
}
