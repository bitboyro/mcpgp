package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ro.bitboy.mcpgp.runtime.diag.Probe;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The P0 support packs (packaging.md §4.1) that need nothing outside the server: audit, introspection,
 * schema, diagnostics (via {@link Probe}) and recording (via {@link ro.bitboy.mcpgp.runtime.call.Recordings}).
 */
final class Packs {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Manifest manifest;
    private final RuntimeConfig config;
    private final ToolCatalog catalog;

    Packs(Manifest manifest, RuntimeConfig config, ToolCatalog catalog) {
        this.manifest = manifest;
        this.config = config;
        this.catalog = catalog;
    }

    /** Handlers by the pack tool's base name (without prefix). */
    Map<String, ToolHandler> handlers() {
        Map<String, ToolHandler> handlers = new LinkedHashMap<>();
        handlers.put("audit_list", (exchange, tool, args, record) -> auditList(args));
        handlers.put("explain_tool", (exchange, tool, args, record) -> explain(args));
        handlers.put("manifest_info", (exchange, tool, args, record) -> manifestInfo());
        handlers.put("describe_schema", (exchange, tool, args, record) -> describeSchema(args));
        handlers.put("tls_certificate_fetch", (exchange, tool, args, record) -> Probe.certificate(instance(args)));
        handlers.put("connectivity_check", (exchange, tool, args, record) -> Probe.connectivity(instance(args)));
        ro.bitboy.mcpgp.runtime.call.Recordings recordings = recordings();
        if (recordings != null) {
            handlers.put("recording_start", (exchange, tool, args, record) -> recording(() -> recordings.start(Toolsets.session(exchange),
                    text(args.get("description")))));
            handlers.put("recording_stop", (exchange, tool, args, record) -> recording(() -> recordings.stop(Toolsets.session(exchange),
                    text(args.get("findings")))));
            handlers.put("recording_list", (exchange, tool, args, record) -> recording(() -> Paging.page(
                    recordings.calls(Toolsets.session(exchange), text(args.get("session"))), args.get("cursor"), limit(args))));
            handlers.put("recording_sessions", (exchange, tool, args, record) -> recording(() -> Paging.page(recordings.sessions(),
                    args.get("cursor"), limit(args))));
        }
        return handlers;
    }

    // ---------------------------------------------------------------------- recording

    private ro.bitboy.mcpgp.runtime.call.Recordings recordings() {
        boolean pack = false;
        for (JsonNode p : manifest.root().path("packs")) {
            pack |= p.path("id").asText().equals("recording");
        }
        if (!pack) {
            return null;
        }
        java.util.Set<String> fields = new java.util.HashSet<>();
        manifest.policies().at("/redaction/fields").forEach(f -> fields.add(f.asText()));
        // the same store the API tools record into (it is shared per storage root)
        return ro.bitboy.mcpgp.runtime.call.Recordings.of(config.storageRoot(), fields, java.util.Set.of(), config.secrets(),
                manifest.policies().path("responseLimitKb").asInt(256) * 1024, config.packs().path("recording").path("retainDays").asInt(14));
    }

    private static Object recording(java.util.function.Supplier<Object> action) {
        try {
            return action.get();
        } catch (IllegalStateException e) {
            throw new ToolError("INPUT_VALIDATION", e.getMessage(), "Use recording_sessions to see the recordings");
        } catch (IllegalArgumentException e) {
            throw new ToolError("INPUT_VALIDATION", e.getMessage());
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static int limit(Map<String, Object> args) {
        return args.get("limit") instanceof Number n ? Math.max(1, Math.min(200, n.intValue())) : 50;
    }

    // ---------------------------------------------------------------------- audit

    private Object auditList(Map<String, Object> args) {
        int limit = args.get("limit") instanceof Number n ? Math.max(1, Math.min(200, n.intValue())) : 50;
        Instant since = time(args.get("since"), "since");
        Instant until = time(args.get("until"), "until");
        List<Map<String, Object>> events = new ArrayList<>();
        for (Path file : auditFiles()) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file);
            } catch (IOException e) {
                continue;
            }
            for (int i = lines.size() - 1; i >= 0; i--) {
                JsonNode event;
                try {
                    event = JSON.readTree(lines.get(i));
                } catch (IOException e) {
                    continue;
                }
                if (!matches(event, "tool", args.get("tool")) || !matches(event, "instance", args.get("instance"))
                        || !matches(event, "outcome", args.get("outcome"))) {
                    continue;
                }
                Instant ts = Instant.parse(event.path("ts").asText("1970-01-01T00:00:00Z"));
                if (since != null && ts.isBefore(since) || until != null && ts.isAfter(until)) {
                    continue;
                }
                events.add(JSON.convertValue(event, Map.class));
            }
        }
        return Paging.page(events, args.get("cursor"), limit);
    }

    /** The current audit file first, then rotated ones, newest first. */
    private List<Path> auditFiles() {
        Path current = config.audit().file().toAbsolutePath();
        List<Path> files = new ArrayList<>();
        if (Files.exists(current)) {
            files.add(current);
        }
        String name = current.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) + "-" : name + "-";
        try (Stream<Path> list = Files.list(current.getParent())) {
            list.filter(p -> p.getFileName().toString().startsWith(base))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .forEach(files::add);
        } catch (IOException e) {
            // no audit directory yet
        }
        return files;
    }

    private static boolean matches(JsonNode event, String field, Object wanted) {
        return wanted == null || wanted.toString().equals(event.path(field).asText());
    }

    private static Instant time(Object value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value.toString());
        } catch (RuntimeException e) {
            throw new ToolError("INPUT_VALIDATION", field + " must be a date-time like 2026-10-10T08:00:00Z");
        }
    }

    // ---------------------------------------------------------------------- introspection

    private Object explain(Map<String, Object> args) {
        JsonNode tool = catalog.tool(String.valueOf(args.get("tool")))
                .orElseThrow(() -> new ToolError("INPUT_VALIDATION", "No tool named '" + args.get("tool") + "'"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", tool.path("name").asText());
        result.put("kind", tool.path("kind").asText());
        if (tool.has("operation")) {
            result.put("operation", JSON.convertValue(tool.get("operation"), Map.class));
            Map<String, Object> variants = new LinkedHashMap<>();
            tool.path("variants").fields().forEachRemaining(v -> variants.put(v.getKey(),
                    v.getValue().path("method").asText() + " " + v.getValue().path("path").asText()));
            result.put("contracts", variants);
        }
        if (tool.has("provenance")) {
            result.put("provenance", JSON.convertValue(tool.get("provenance"), Map.class));
        }
        return result;
    }

    private Object manifestInfo() {
        JsonNode root = manifest.root();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("server", JSON.convertValue(root.path("server"), Map.class));
        info.put("build", Map.of("createdAt", root.at("/build/createdAt").asText(), "cli", root.at("/build/cli").asText()));
        List<Map<String, Object>> apis = new ArrayList<>();
        manifest.apis().values().forEach(api -> apis.add(Map.of("id", api.id(), "title", api.title(),
                "contracts", List.copyOf(api.contracts().keySet()), "defaultContract", api.defaultContract())));
        info.put("apis", apis);
        info.put("instances", root.at("/instances/mode").asText());
        info.put("exposure", root.at("/exposure/mode").asText());
        List<String> packs = new ArrayList<>();
        root.path("packs").forEach(p -> packs.add(p.path("id").asText()));
        info.put("packs", packs);
        long apiTools = catalog.all().values().stream().filter(t -> t.path("kind").asText().equals("api")).count();
        info.put("tools", Map.of("api", apiTools, "listed", catalog.listed().size()));
        return info;
    }

    // ---------------------------------------------------------------------- schema

    /**
     * The input schema one level deep: properties with type and description; nested objects are
     * listed by name. {@code part} drills into one property path ({@code body.payload}) or a
     * {@code $defs} entry; {@code variant} narrows a {@code oneOf} by index.
     */
    private Object describeSchema(Map<String, Object> args) {
        JsonNode tool = catalog.tool(String.valueOf(args.get("tool")))
                .orElseThrow(() -> new ToolError("INPUT_VALIDATION", "No tool named '" + args.get("tool") + "'"));
        JsonNode schema = tool.path("inputSchema");
        JsonNode defs = schema.path("$defs");
        String part = args.get("part") == null ? null : args.get("part").toString();
        JsonNode node = schema;
        if (part != null) {
            node = defs.has(part) ? defs.get(part) : null;
            if (node == null) {
                node = schema;
                for (String segment : part.split("\\.")) {
                    node = resolve(node, defs).path("properties").path(segment);
                }
                if (node.isMissingNode()) {
                    throw new ToolError("INPUT_VALIDATION", "No part '" + part + "' in " + tool.path("name").asText(),
                            "Use a property path like body.payload, or a definition name");
                }
            }
        }
        node = resolve(node, defs);
        if (args.get("variant") != null) {
            int index;
            try {
                index = Integer.parseInt(args.get("variant").toString());
            } catch (NumberFormatException e) {
                throw new ToolError("INPUT_VALIDATION", "variant must be the index of a oneOf alternative");
            }
            if (!node.path("oneOf").has(index)) {
                throw new ToolError("INPUT_VALIDATION", "This part has no oneOf alternative " + index);
            }
            node = resolve(node.path("oneOf").get(index), defs);
        }
        return summarize(node, defs);
    }

    private static JsonNode resolve(JsonNode node, JsonNode defs) {
        JsonNode current = node;
        for (int i = 0; i < 8 && current.path("$ref").asText("").startsWith("#/$defs/"); i++) {
            current = defs.path(current.get("$ref").asText().substring("#/$defs/".length()));
        }
        return current;
    }

    private static Map<String, Object> summarize(JsonNode node, JsonNode defs) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", node.path("type").isMissingNode() ? (node.has("oneOf") ? "oneOf" : "any") : JSON.convertValue(node.path("type"), Object.class));
        if (node.has("description")) {
            summary.put("description", node.path("description").asText());
        }
        if (node.has("properties")) {
            List<String> required = new ArrayList<>();
            node.path("required").forEach(r -> required.add(r.asText()));
            Map<String, Object> properties = new LinkedHashMap<>();
            node.path("properties").fields().forEachRemaining(f -> {
                JsonNode p = resolve(f.getValue(), defs);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("type", p.has("type") ? JSON.convertValue(p.path("type"), Object.class) : p.has("oneOf") ? "oneOf" : "any");
                if (required.contains(f.getKey())) {
                    line.put("required", true);
                }
                if (p.has("description")) {
                    line.put("description", p.path("description").asText());
                }
                if (p.has("enum")) {
                    line.put("enum", JSON.convertValue(p.path("enum"), List.class));
                }
                if (p.has("properties") || p.has("oneOf") || p.path("items").has("properties")) {
                    line.put("more", "describe_schema with part=<path>.<name> for its fields");
                }
                properties.put(f.getKey(), line);
            });
            summary.put("properties", properties);
        }
        if (node.has("oneOf")) {
            List<Object> variants = new ArrayList<>();
            node.path("oneOf").forEach(v -> variants.add(resolve(v, defs).path("required")));
            summary.put("oneOf", JSON.convertValue(variants, List.class));
            summary.put("more", "describe_schema with variant=<index> for one alternative");
        }
        if (node.has("items")) {
            summary.put("items", summarize(resolve(node.path("items"), defs), defs));
        }
        return summary;
    }

    // ---------------------------------------------------------------------- diagnostics

    private RuntimeConfig.Instance instance(Map<String, Object> args) {
        List<RuntimeConfig.Instance> all = config.allInstances();
        if (!manifest.multiInstance()) {
            return all.get(0);
        }
        Object name = args.get("instance");
        List<String> names = all.stream().map(RuntimeConfig.Instance::id).toList();
        if (name == null) {
            throw new ToolError("INSTANCE_REQUIRED", "instance is required", "One of: " + String.join(", ", names));
        }
        return all.stream().filter(i -> i.id().equals(name.toString())).findFirst()
                .orElseThrow(() -> new ToolError("INSTANCE_UNKNOWN", "No instance named '" + name + "'", "One of: " + String.join(", ", names)));
    }

}
