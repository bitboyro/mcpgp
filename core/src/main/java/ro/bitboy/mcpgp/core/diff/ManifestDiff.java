package ro.bitboy.mcpgp.core.diff;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * What changed between two builds of a server, from their manifests (cli.md §3.5, platform-api.md
 * §2.1): tools added, removed and renamed (matched by their operation, so a rename isn't a removal),
 * their inputs and outputs field by field, annotations, the contract versions they work with, and
 * the server's own settings. Each change says whether it breaks agents or clients that use the
 * earlier build.
 */
public final class ManifestDiff {

    /** How much a change matters to whoever uses the earlier build. */
    public enum Impact { BREAKING, NOTABLE, INFO }

    /**
     * @param tool the tool's name in the later build (the earlier one's for a removed tool); null for the server
     * @param kind {@code tool.added}, {@code tool.removed}, {@code tool.renamed}, {@code input.added}, … (stable, for machines)
     */
    public record Change(Impact impact, String tool, String kind, String text) {
    }

    /** The two builds' versions and what changed, breaking changes first. */
    public record Result(String from, String to, List<Change> changes) {
        public long breaking() {
            return changes.stream().filter(c -> c.impact() == Impact.BREAKING).count();
        }

        public boolean empty() {
            return changes.isEmpty();
        }
    }

    private ManifestDiff() {
    }

    public static Result compare(JsonNode before, JsonNode after) {
        return compare(before, after, false);
    }

    /**
     * @param contractsReplaced the server follows its API's current contract only, so a contract version that
     *                          is gone is a replacement, not a break: {@code contract.removed} becomes notable and
     *                          {@code tool.contract.removed} informational. Everything else keeps its impact.
     */
    public static Result compare(JsonNode before, JsonNode after, boolean contractsReplaced) {
        List<Change> changes = new ArrayList<>();
        server(before, after, changes);
        Map<String, JsonNode> was = tools(before);
        Map<String, JsonNode> now = tools(after);
        for (Map.Entry<String, JsonNode> e : now.entrySet()) {
            JsonNode old = was.get(e.getKey());
            JsonNode tool = e.getValue();
            String name = tool.path("name").asText();
            if (old == null) {
                changes.add(new Change(Impact.INFO, name, "tool.added", "new tool " + name + describeOperation(tool)));
                continue;
            }
            tool(old, tool, changes);
        }
        for (Map.Entry<String, JsonNode> e : was.entrySet()) {
            if (!now.containsKey(e.getKey())) {
                String name = e.getValue().path("name").asText();
                changes.add(new Change(Impact.BREAKING, name, "tool.removed", "tool " + name + " removed" + describeOperation(e.getValue())));
            }
        }
        if (contractsReplaced) {
            changes.replaceAll(c -> switch (c.kind()) {
                case "contract.removed" -> new Change(Impact.NOTABLE, c.tool(), c.kind(), c.text().replace(
                        "removed: target servers on it can't be used any more", "replaced: target servers on it can't be used any more"));
                case "tool.contract.removed" -> new Change(Impact.INFO, c.tool(), c.kind(), c.text());
                default -> c;
            });
        }
        changes.sort((a, b) -> a.impact() != b.impact() ? a.impact().compareTo(b.impact())
                : Objects.toString(a.tool(), "").compareTo(Objects.toString(b.tool(), "")));
        return new Result(before.at("/server/version").asText(""), after.at("/server/version").asText(""), List.copyOf(changes));
    }

    // ---------------------------------------------------------------------- the server

    private static void server(JsonNode before, JsonNode after, List<Change> changes) {
        Map<String, JsonNode> apisBefore = byId(before.path("apis"));
        Map<String, JsonNode> apisAfter = byId(after.path("apis"));
        for (Map.Entry<String, JsonNode> e : apisAfter.entrySet()) {
            JsonNode old = apisBefore.get(e.getKey());
            if (old == null) {
                changes.add(new Change(Impact.INFO, null, "api.added", "new API " + e.getKey()));
                continue;
            }
            Set<String> versionsBefore = versions(old);
            Set<String> versionsAfter = versions(e.getValue());
            for (String v : versionsAfter) {
                if (!versionsBefore.contains(v)) {
                    changes.add(new Change(Impact.INFO, null, "contract.added", e.getKey() + ": contract " + v + " added"));
                }
            }
            for (String v : versionsBefore) {
                if (!versionsAfter.contains(v)) {
                    changes.add(new Change(Impact.BREAKING, null, "contract.removed", e.getKey() + ": contract " + v
                            + " removed: target servers on it can't be used any more"));
                }
            }
            String defaultBefore = defaultVersion(old);
            String defaultAfter = defaultVersion(e.getValue());
            if (!Objects.equals(defaultBefore, defaultAfter)) {
                changes.add(new Change(Impact.NOTABLE, null, "contract.default", e.getKey() + ": the default contract is now " + defaultAfter
                        + " (was " + defaultBefore + ")"));
            }
            setChange(upstreamAuth(old).path("supported"), upstreamAuth(e.getValue()).path("supported"), e.getKey() + ": sign-in types",
                    "auth", changes);
            if (!old.path("toolPrefix").asText().equals(e.getValue().path("toolPrefix").asText())) {
                changes.add(new Change(Impact.NOTABLE, null, "api.prefix", e.getKey() + ": tool prefix " + old.path("toolPrefix").asText()
                        + " → " + e.getValue().path("toolPrefix").asText()));
            }
        }
        for (String id : apisBefore.keySet()) {
            if (!apisAfter.containsKey(id)) {
                changes.add(new Change(Impact.BREAKING, null, "api.removed", "API " + id + " removed"));
            }
        }
        value(before.at("/exposure/mode"), after.at("/exposure/mode"), "how tools reach the agent (exposure)", Impact.NOTABLE, "exposure", changes);
        value(before.at("/instances/mode"), after.at("/instances/mode"), "target servers (instances)", Impact.BREAKING, "instances", changes);
        value(before.at("/instances/mixedContracts"), after.at("/instances/mixedContracts"), "different versions across targets",
                Impact.NOTABLE, "mixedContracts", changes);
        setChange(before.at("/runtime/transports/allowed"), after.at("/runtime/transports/allowed"), "transports", "transports", changes);
        setChange(before.at("/runtime/inboundAuth/allowed"), after.at("/runtime/inboundAuth/allowed"), "client sign-in modes", "inboundAuth", changes);
        Set<String> packsBefore = ids(before.path("packs"));
        Set<String> packsAfter = ids(after.path("packs"));
        packsAfter.stream().filter(p -> !packsBefore.contains(p))
                .forEach(p -> changes.add(new Change(Impact.INFO, null, "pack.added", "support pack " + p + " added")));
        packsBefore.stream().filter(p -> !packsAfter.contains(p))
                .forEach(p -> changes.add(new Change(Impact.NOTABLE, null, "pack.removed", "support pack " + p + " removed")));
        value(before.at("/policies/confirmDestructive"), after.at("/policies/confirmDestructive"), "confirmation of destructive calls",
                Impact.NOTABLE, "confirmDestructive", changes);
    }

    private static void value(JsonNode before, JsonNode after, String what, Impact impact, String kind, List<Change> changes) {
        if (!before.equals(after) && !(before.isMissingNode() && after.isMissingNode())) {
            changes.add(new Change(impact, null, "server." + kind, what + ": " + text(before) + " → " + text(after)));
        }
    }

    private static void setChange(JsonNode before, JsonNode after, String what, String kind, List<Change> changes) {
        Set<String> was = texts(before);
        Set<String> now = texts(after);
        Set<String> gone = new LinkedHashSet<>(was);
        gone.removeAll(now);
        Set<String> added = new LinkedHashSet<>(now);
        added.removeAll(was);
        if (!gone.isEmpty()) {
            changes.add(new Change(Impact.BREAKING, null, "server." + kind + ".removed", what + ": " + String.join(", ", gone) + " no longer offered"));
        }
        if (!added.isEmpty()) {
            changes.add(new Change(Impact.INFO, null, "server." + kind + ".added", what + ": " + String.join(", ", added) + " added"));
        }
    }

    // ---------------------------------------------------------------------- tools

    /** Tools by what they are: an API tool by its operation (and content type), others by name. */
    private static Map<String, JsonNode> tools(JsonNode manifest) {
        Map<String, JsonNode> tools = new LinkedHashMap<>();
        for (JsonNode tool : manifest.path("tools")) {
            String key = tool.path("kind").asText().equals("api") && tool.at("/operation/key").isTextual()
                    ? "api:" + tool.at("/operation/key").asText() + contentTypePart(tool)
                    : tool.path("kind").asText() + ":" + tool.path("name").asText();
            tools.put(key, tool);
        }
        return tools;
    }

    /** What a content type adds to an operation's tool name ({@code _multipart}), from the name's provenance. */
    private static String contentTypePart(JsonNode tool) {
        String namespaced = null;
        String suffixed = null;
        for (JsonNode step : tool.at("/provenance/name")) {
            if (step.path("step").asText().equals("namespace")) {
                namespaced = step.path("value").asText();
            }
            if (step.path("step").asText().equals("contentTypeSuffix")) {
                suffixed = step.path("value").asText();
            }
        }
        return suffixed != null && namespaced != null && suffixed.startsWith(namespaced) ? "#" + suffixed.substring(namespaced.length()) : "";
    }

    private static void tool(JsonNode old, JsonNode tool, List<Change> changes) {
        String name = tool.path("name").asText();
        String was = old.path("name").asText();
        if (!was.equals(name)) {
            changes.add(new Change(Impact.BREAKING, name, "tool.renamed", "renamed from " + was + ": agents and clients that call " + was + " need updating"));
        }
        if (!old.path("title").asText("").equals(tool.path("title").asText(""))) {
            changes.add(new Change(Impact.INFO, name, "tool.title", "title: \"" + old.path("title").asText("") + "\" → \"" + tool.path("title").asText("") + "\""));
        }
        if (!old.path("description").asText("").equals(tool.path("description").asText(""))) {
            changes.add(new Change(Impact.INFO, name, "tool.description", "description changed"));
        }
        annotation(old, tool, "readOnlyHint", "read-only", name, changes);
        annotation(old, tool, "destructiveHint", "destructive", name, changes);
        if (old.path("confirm").asBoolean(false) != tool.path("confirm").asBoolean(false)) {
            changes.add(new Change(Impact.NOTABLE, name, "tool.confirm", tool.path("confirm").asBoolean(false)
                    ? "now asks for confirmation" : "no longer asks for confirmation"));
        }
        Set<String> contractsBefore = keys(old.path("variants"));
        Set<String> contractsAfter = keys(tool.path("variants"));
        contractsBefore.stream().filter(c -> !contractsAfter.contains(c)).forEach(c ->
                changes.add(new Change(Impact.BREAKING, name, "tool.contract.removed", "no longer works with contract " + c)));
        contractsAfter.stream().filter(c -> !contractsBefore.contains(c)).forEach(c ->
                changes.add(new Change(Impact.INFO, name, "tool.contract.added", "now works with contract " + c)));
        fields(old.path("inputSchema"), tool.path("inputSchema"), true, name, changes);
        fields(old.path("outputSchema"), tool.path("outputSchema"), false, name, changes);
    }

    private static void annotation(JsonNode old, JsonNode tool, String hint, String word, String name, List<Change> changes) {
        boolean was = old.at("/annotations/" + hint).asBoolean(false);
        boolean now = tool.at("/annotations/" + hint).asBoolean(false);
        if (was != now) {
            changes.add(new Change(Impact.NOTABLE, name, "tool.annotation", now ? "now marked " + word : "no longer marked " + word));
        }
    }

    /** A field of a schema: where it is ({@code body.items[].name}), its type, whether it's required. */
    private record Field(String type, boolean required) {
    }

    private static void fields(JsonNode before, JsonNode after, boolean input, String tool, List<Change> changes) {
        Map<String, Field> was = flatten(before);
        Map<String, Field> now = flatten(after);
        String side = input ? "input" : "output";
        for (Map.Entry<String, Field> e : now.entrySet()) {
            Field old = was.get(e.getKey());
            Field field = e.getValue();
            if (old == null) {
                // a new required input breaks callers that don't send it; anything else is additive
                Impact impact = input && field.required() ? Impact.BREAKING : Impact.INFO;
                changes.add(new Change(impact, tool, side + ".added", side + " " + e.getKey() + " added" + (input && field.required() ? " (required)" : "")));
                continue;
            }
            if (!old.type().equals(field.type())) {
                changes.add(new Change(Impact.BREAKING, tool, side + ".type", side + " " + e.getKey() + ": " + old.type() + " → " + field.type()));
            }
            if (input && !old.required() && field.required()) {
                changes.add(new Change(Impact.BREAKING, tool, "input.required", "input " + e.getKey() + " is now required"));
            }
            if (input && old.required() && !field.required()) {
                changes.add(new Change(Impact.INFO, tool, "input.optional", "input " + e.getKey() + " is now optional"));
            }
        }
        for (String path : was.keySet()) {
            if (!now.containsKey(path)) {
                // a removed input breaks callers that send it; a removed output breaks those that read it
                changes.add(new Change(Impact.BREAKING, tool, side + ".removed", side + " " + path + " removed"));
            }
        }
    }

    private static Map<String, Field> flatten(JsonNode schema) {
        Map<String, Field> fields = new TreeMap<>();
        flatten(schema, schema.path("$defs"), "", fields, new HashSet<>(), 0);
        return fields;
    }

    private static void flatten(JsonNode schema, JsonNode defs, String prefix, Map<String, Field> fields, Set<String> seen, int depth) {
        JsonNode resolved = resolve(schema, defs, seen);
        if (resolved == null || depth > 8) {
            return;
        }
        Set<String> required = texts(resolved.path("required"));
        resolved.path("properties").fields().forEachRemaining(p -> {
            String path = prefix.isEmpty() ? p.getKey() : prefix + "." + p.getKey();
            JsonNode property = p.getValue();
            fields.put(path, new Field(type(property, defs), required.contains(p.getKey())));
            Set<String> branch = new HashSet<>(seen);
            flatten(property, defs, path, fields, branch, depth + 1);
            JsonNode items = resolve(property, defs, new HashSet<>(seen));
            if (items != null && items.has("items")) {
                flatten(items.get("items"), defs, path + "[]", fields, new HashSet<>(seen), depth + 1);
            }
        });
    }

    /** Follows a {@code #/$defs/…} reference once per path (recursive schemas end there). */
    private static JsonNode resolve(JsonNode schema, JsonNode defs, Set<String> seen) {
        JsonNode current = schema;
        while (current != null && current.path("$ref").asText("").startsWith("#/$defs/")) {
            String name = current.get("$ref").asText().substring("#/$defs/".length());
            if (!seen.add(name)) {
                return null;
            }
            current = defs.get(name);
        }
        return current;
    }

    private static String type(JsonNode property, JsonNode defs) {
        JsonNode resolved = resolve(property, defs, new HashSet<>());
        if (resolved == null) {
            return property.path("$ref").asText("object");
        }
        JsonNode type = resolved.get("type");
        if (type == null) {
            return resolved.has("oneOf") ? "oneOf" : resolved.has("anyOf") ? "anyOf" : resolved.has("properties") ? "object" : "any";
        }
        if (type.isArray()) {
            List<String> types = new ArrayList<>();
            type.forEach(t -> types.add(t.asText()));
            return String.join("|", types);
        }
        String t = type.asText();
        if (t.equals("array") && resolved.has("items")) {
            return "array of " + type(resolved.get("items"), defs);
        }
        return resolved.has("format") ? t + " (" + resolved.get("format").asText() + ")" : t;
    }

    // ---------------------------------------------------------------------- helpers

    private static String describeOperation(JsonNode tool) {
        String key = tool.at("/operation/key").asText("");
        String[] parts = key.split(":", 3);
        return parts.length == 3 ? " (" + parts[1] + " " + parts[2] + ")" : "";
    }

    private static Map<String, JsonNode> byId(JsonNode list) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        list.forEach(a -> map.put(a.path("id").asText(), a));
        return map;
    }

    private static Set<String> versions(JsonNode api) {
        Set<String> versions = new LinkedHashSet<>();
        api.path("contracts").forEach(c -> versions.add(c.path("version").asText()));
        return versions;
    }

    private static String defaultVersion(JsonNode api) {
        for (JsonNode c : api.path("contracts")) {
            if (c.path("default").asBoolean(false)) {
                return c.path("version").asText();
            }
        }
        return null;
    }

    private static Set<String> ids(JsonNode list) {
        Set<String> ids = new LinkedHashSet<>();
        list.forEach(p -> ids.add(p.path("id").asText()));
        return ids;
    }

    private static Set<String> texts(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }

    private static Set<String> keys(JsonNode object) {
        Set<String> keys = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static String text(JsonNode value) {
        return value.isMissingNode() || value.isNull() ? "none" : value.isTextual() ? value.asText() : value.toString();
    }

    /** An API entry's {@code upstreamAuth}; {@code downstreamAuth} in a manifest built before 0.4.0. */
    private static JsonNode upstreamAuth(JsonNode api) {
        return api.has("upstreamAuth") ? api.path("upstreamAuth") : api.path("downstreamAuth");
    }
}
