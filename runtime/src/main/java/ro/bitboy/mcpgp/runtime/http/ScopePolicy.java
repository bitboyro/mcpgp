package ro.bitboy.mcpgp.runtime.http;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which tools a token's scopes allow ({@code server.auth.scopes}, auth.md §1.1): each scope names a
 * selection, {@code access} (read-only: tools that only read; read-write: all but destructive ones;
 * all) and optionally {@code tools} (names, {@code *} as a wildcard). Without a mapping, any valid
 * token allows every tool. Built-in tools (instances, discovery) are allowed to any valid token; what
 * they reach is checked on its own.
 */
public final class ScopePolicy {

    private record Selection(String access, List<Pattern> tools) {
    }

    private final Map<String, Selection> scopes = new LinkedHashMap<>();

    public ScopePolicy(JsonNode config) {
        if (config == null) {
            return;
        }
        config.properties().forEach(e -> {
            List<Pattern> tools = new ArrayList<>();
            e.getValue().path("tools").forEach(t -> tools.add(glob(t.asText())));
            scopes.put(e.getKey(), new Selection(e.getValue().path("access").asText("all"), tools));
        });
    }

    boolean restricts() {
        return !scopes.isEmpty();
    }

    public boolean allows(JsonNode tool, Set<String> granted) {
        if (!restricts() || tool.path("kind").asText().equals("builtin")) {
            return true;
        }
        for (String scope : granted) {
            Selection selection = scopes.get(scope);
            if (selection != null && selects(selection, tool)) {
                return true;
            }
        }
        return false;
    }

    /** The scopes that would allow the tool, for {@code insufficient_scope} answers. */
    List<String> needed(JsonNode tool) {
        List<String> needed = new ArrayList<>();
        scopes.forEach((scope, selection) -> {
            if (selects(selection, tool)) {
                needed.add(scope);
            }
        });
        return needed;
    }

    private static boolean selects(Selection selection, JsonNode tool) {
        boolean byAccess = switch (selection.access()) {
            case "read-only" -> tool.at("/annotations/readOnlyHint").asBoolean(false);
            case "read-write" -> !tool.at("/annotations/destructiveHint").asBoolean(false);
            default -> true;
        };
        if (!byAccess) {
            return false;
        }
        if (selection.tools().isEmpty()) {
            return true;
        }
        String name = tool.path("name").asText();
        return selection.tools().stream().anyMatch(p -> p.matcher(name).matches());
    }

    private static Pattern glob(String text) {
        List<String> parts = new ArrayList<>();
        for (String part : text.split("\\*", -1)) {
            parts.add(Pattern.quote(part));
        }
        return Pattern.compile(String.join(".*", parts));
    }
}
