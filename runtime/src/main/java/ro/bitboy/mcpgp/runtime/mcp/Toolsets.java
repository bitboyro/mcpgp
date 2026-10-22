package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * {@code toolsets} exposure (tools-contract §15.3): groups of API tools, from the manifest, that each
 * session enables and disables. {@code tools/list} shows the built-in and pack tools plus the tools of
 * the session's enabled toolsets; enabling or disabling one sends that session
 * {@code notifications/tools/list_changed}. One per process, so a config reload keeps what sessions enabled.
 */
public final class Toolsets {

    private static final Map<Manifest, Toolsets> SHARED = new ConcurrentHashMap<>();

    private final Map<String, String> descriptions = new LinkedHashMap<>();
    private final Map<String, Set<String>> members = new LinkedHashMap<>();
    /** Tool → its toolsets. */
    private final Map<String, Set<String>> owners = new ConcurrentHashMap<>();
    /** Session → its enabled toolsets. */
    private final Map<String, Set<String>> enabled = new ConcurrentHashMap<>();
    private volatile Consumer<String> listChanged = session -> { };

    private Toolsets(Manifest manifest) {
        manifest.exposure().path("toolsets").properties().forEach(e -> {
            descriptions.put(e.getKey(), e.getValue().path("description").asText(""));
            Set<String> tools = new LinkedHashSet<>();
            e.getValue().path("tools").forEach(t -> {
                tools.add(t.asText());
                owners.computeIfAbsent(t.asText(), k -> new LinkedHashSet<>()).add(e.getKey());
            });
            members.put(e.getKey(), tools);
        });
    }

    /** The toolsets of this manifest, shared by every server factory made from it. */
    static Toolsets of(Manifest manifest) {
        return SHARED.computeIfAbsent(manifest, Toolsets::new);
    }

    static boolean active(Manifest manifest) {
        return manifest.exposure().path("mode").asText("plain").equals("toolsets");
    }

    /** How the transport tells a session its tool list changed. */
    public void onListChanged(Consumer<String> listener) {
        this.listChanged = listener;
    }

    public Set<String> enabled(String session) {
        return Set.copyOf(enabled.getOrDefault(key(session), Set.of()));
    }

    /** The API tools a session sees: those of its enabled toolsets. */
    public Set<String> visible(String session) {
        Set<String> tools = new LinkedHashSet<>();
        enabled(session).forEach(set -> tools.addAll(members.getOrDefault(set, Set.of())));
        return tools;
    }

    /** The toolsets a tool belongs to, when none of them is enabled for the session; empty when it may be called. */
    Optional<List<String>> blocked(String tool, String session) {
        Set<String> sets = owners.get(tool);
        if (sets == null) {
            return Optional.of(List.of());          // in no toolset: never listed, never callable
        }
        Set<String> on = enabled(session);
        return sets.stream().anyMatch(on::contains) ? Optional.empty() : Optional.of(List.copyOf(sets));
    }

    Map<String, ToolHandler> handlers(ToolCatalog catalog) {
        Map<String, ToolHandler> handlers = new LinkedHashMap<>();
        handlers.put("toolsets_list", (exchange, tool, args, record) -> list(session(exchange), catalog));
        handlers.put("toolset_enable", (exchange, tool, args, record) -> change(session(exchange), args, true, catalog));
        handlers.put("toolset_disable", (exchange, tool, args, record) -> change(session(exchange), args, false, catalog));
        return handlers;
    }

    private Object list(String session, ToolCatalog catalog) {
        Set<String> on = enabled(session);
        List<Map<String, Object>> sets = new ArrayList<>();
        members.forEach((name, tools) -> {
            long count = tools.stream().filter(t -> !catalog.servedAs(t).isEmpty()).count();
            if (count == 0) {
                return;
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("name", name);
            line.put("description", descriptions.get(name));
            line.put("tools", count);
            line.put("enabled", on.contains(name));
            sets.add(line);
        });
        return Map.of("toolsets", sets);
    }

    private Object change(String session, Map<String, Object> args, boolean on, ToolCatalog catalog) {
        String name = String.valueOf(args.getOrDefault("name", ""));
        if (!members.containsKey(name) || members.get(name).stream().noneMatch(t -> !catalog.servedAs(t).isEmpty())) {
            throw new ToolError("INPUT_VALIDATION", "No toolset named '" + name + "'", "Use toolsets_list to see them");
        }
        Set<String> sets = enabled.computeIfAbsent(key(session), k -> ConcurrentHashMap.newKeySet());
        boolean changed = on ? sets.add(name) : sets.remove(name);
        if (changed) {
            listChanged.accept(session);
        }
        return Map.of("toolset", name, "enabled", on, "tools", members.get(name).stream().filter(t -> !catalog.servedAs(t).isEmpty()).count());
    }

    /** A session's end: forget what it enabled. */
    public void forget(String session) {
        enabled.remove(key(session));
    }

    static String session(io.modelcontextprotocol.server.McpSyncServerExchange exchange) {
        return exchange == null ? null : exchange.sessionId();
    }

    private static String key(String session) {
        return session == null ? "" : session;
    }
}
