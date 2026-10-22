package ro.bitboy.mcpgp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import ro.bitboy.mcpgp.runtime.config.ConfigException;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.config.ToolPolicy;
import ro.bitboy.mcpgp.runtime.mcp.Ids;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Administrator-only, previewed edits of tool policies in the unresolved source config. */
public final class PolicyManagement {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final int SOURCE_LIMIT = 1024 * 1024;
    private static final Duration LIFETIME = Duration.ofMinutes(10);
    private static final java.util.regex.Pattern PATH = java.util.regex.Pattern.compile(
            "^/(?:apis/[^/]+/)?(?:tools|instance/tools|instances/(?:0|[1-9][0-9]*)/tools)$");

    public static final class Failure extends RuntimeException {
        private final int status;
        private final String code;
        public Failure(int status, String code, String message) {
            super(message, null, false, false);
            this.status = status;
            this.code = code;
        }
        public int status() { return status; }
        public String code() { return code; }
    }

    @FunctionalInterface
    public interface Persistence {
        List<String> replace(Path file, byte[] expected, byte[] candidate) throws IOException;
    }

    private record State(Path file, byte[] bytes, ObjectNode document, RuntimeConfig desired, String revision) { }
    private record Plan(String actor, String revision, ObjectNode document, List<String> paths, Instant expires) { }
    private final Reloader reloader;
    private final Clock clock;
    private final Persistence persistence;
    // Guarded by reloader, also used by file reload: a single transaction lock.
    private final Map<String, Plan> previews = new LinkedHashMap<>();

    public PolicyManagement(Reloader reloader) {
        this(reloader, Clock.systemUTC(), PolicyManagement::replace);
    }

    public PolicyManagement(Reloader reloader, Clock clock, Persistence persistence) {
        this.reloader = reloader;
        this.clock = clock;
        this.persistence = persistence;
    }

    public Map<String, Object> read(String actor) {
        synchronized (reloader) {
            State state = state();
            Map<String, Object> result = status(state);
            audit("read", actor, "ok", null, state.revision(), state.revision(), List.of());
            return result;
        }
    }

    public Map<String, Object> preview(JsonNode request, String actor) {
        synchronized (reloader) {
            fields(request, Set.of("expectedRevision", "changes"));
            State before = state();
            expected(request, before);
            JsonNode changes = request.path("changes");
            if (!changes.isArray() || changes.isEmpty() || changes.size() > 64) {
                throw invalid("Supply between 1 and 64 policy changes");
            }
            ObjectNode document = before.document().deepCopy();
            List<String> paths = new ArrayList<>();
            List<Map<String, Object>> local = new ArrayList<>();
            for (JsonNode change : changes) {
                fields(change, Set.of("path", "value"));
                String path = change.path("path").asText("");
                if (!change.has("value") || path.length() > 256 || !ro.bitboy.mcpgp.runtime.config.SettingRegistry.editable(path) || paths.contains(path)) {
                    throw invalid("Changes must use distinct supported tool-policy paths and include a value");
                }
                int last = path.lastIndexOf('/');
                JsonNode parent = parent(document, path.substring(0, last));
                if (!(parent instanceof ObjectNode object)) {
                    throw invalid("A policy's API or instance must already exist");
                }
                JsonNode value = change.get("value");
                boolean policy = ro.bitboy.mcpgp.runtime.config.SettingRegistry.policy(path);
                if (value.toString().contains("${")) { throw invalid("Managed values must be literals, never secret/file references"); }
                if (policy && !value.isNull()) {
                    fields(value, Set.of("allow", "deny", "readOnly"));
                    for (String key : List.of("allow", "deny")) {
                        if (value.has(key)) {
                            if (!value.path(key).isArray() || value.path(key).size() > 2048) {
                                throw invalid("Policy allow and deny must be arrays of tool names");
                            }
                            for (JsonNode name : value.path(key)) {
                                if (!name.isTextual() || name.asText().contains("${") || name.asText().length() > 256) {
                                    throw invalid("Policy entries must be literal tool names or globs");
                                }
                            }
                        }
                    }
                }
                String field = path.substring(last + 1);
                JsonNode previous = object.get(field);
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("path", path);
                diff.put("before", previous == null ? null : previous.deepCopy());
                diff.put("after", value.deepCopy());
                local.add(diff);
                if (value.isNull()) {
                    object.remove(field);
                } else {
                    object.set(field, value.deepCopy());
                }
                paths.add(path);
            }
            if (document.equals(before.document())) {
                throw new Failure(409, "POLICY_NO_CHANGE", "The source policies already have these values");
            }
            RuntimeConfig loaded = validate(document, paths);
            previews.entrySet().removeIf(e -> !clock.instant().isBefore(e.getValue().expires()));
            while (previews.size() >= 64) {
                previews.remove(previews.keySet().iterator().next());
            }
            String id = UUID.randomUUID().toString();
            Instant expires = clock.instant().plus(LIFETIME);
            previews.put(id, new Plan(actor, before.revision(), document, List.copyOf(paths), expires));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("revision", before.revision());
            result.put("previewId", id);
            result.put("expiresAt", expires.toString());
            result.put("changes", local);
            result.put("decisions", difference(decisions(before.desired()), decisions(loaded)));
            List<String> warnings = new ArrayList<>(policyWarnings(loaded));
            if (!json(before.file())) {
                warnings.add("Applying serializes YAML; comments and formatting are not retained.");
            }
            result.put("warnings", warnings);
            result.put("activeConfigHash", reloader.current().config().configHash());
            result.put("desiredConfigHash", loaded.configHash());
            result.put("restartRequired", reloader.restartRequired(loaded));
            result.put("rewritesYamlFormatting", !json(before.file()));
            audit("preview", actor, "ok", null, before.revision(), before.revision(), paths);
            return result;
        }
    }

    public Map<String, Object> apply(JsonNode request, String actor) {
        synchronized (reloader) {
            fields(request, Set.of("expectedRevision", "previewId"));
            State before = state();
            expected(request, before);
            String id = request.path("previewId").asText("");
            Plan plan = previews.get(id);
            if (plan == null || !plan.actor().equals(actor) || !clock.instant().isBefore(plan.expires())) {
                throw new Failure(409, "PREVIEW_EXPIRED", "Create a fresh preview with this administrator key");
            }
            if (!plan.revision().equals(before.revision())) {
                throw conflict();
            }
            RuntimeConfig loaded = validate(plan.document(), plan.paths());
            byte[] serialized;
            try {
                serialized = (json(before.file()) ? JSON.writerWithDefaultPrettyPrinter() : YAML.writer())
                        .writeValueAsBytes(plan.document());
            } catch (IOException e) {
                throw new Failure(503, "CONFIG_STORAGE_UNAVAILABLE", "The candidate could not be serialized");
            }
            // Constructor failures must occur before the config file is replaced.
            var prepared = reloader.prepare(loaded);
            if (!state().revision().equals(before.revision())) {
                throw conflict();
            }
            List<String> warnings = new ArrayList<>();
            try {
                warnings.addAll(persistence.replace(before.file(), before.bytes(), serialized));
            } catch (IOException e) {
                throw new Failure(503, "CONFIG_STORAGE_UNAVAILABLE", "The config could not be persisted; active policy is unchanged");
            }
            try {
                reloader.install(prepared, loaded, "management", actor);
            } catch (RuntimeException e) {
                // handOver publishes the snapshot before refreshing SDK registrations. Do not invite
                // a blind retry after disk replacement and policy publication have succeeded.
                warnings.add("Policy persisted and activated; catalog refresh was incomplete. Inspect status and restart if needed.");
            }
            previews.clear();
            State after = state();
            Map<String, Object> result = status(after);
            result.put("persisted", true);
            result.put("warnings", warnings);
            audit("apply", actor, "ok", null, before.revision(), after.revision(), plan.paths());
            return result;
        }
    }

    private RuntimeConfig validate(ObjectNode document, List<String> paths) {
        RuntimeConfig loaded;
        try {
            loaded = reloader.previewConfig(document);
        } catch (ConfigException e) {
            throw new Failure(422, "CONFIG_INVALID", "The candidate failed runtime configuration validation");
        }
        for (String path : paths) {
            boolean overridden = false;
            for (String kind : List.of("_environment", "_flags")) {
                for (JsonNode source : loaded.document().path(kind)) {
                    String pointer = source.asText();
                    if (pointer.equals(path) || pointer.startsWith(path + "/") || path.startsWith(pointer + "/")) { overridden = true; }
                }
            }
            if (overridden && !document.at(path).equals(loaded.document().at(path))) {
                throw new Failure(409, "POLICY_OVERRIDDEN", "An environment or CLI override masks a requested policy edit");
            }
        }
        try { reloader.activeConfig(loaded); } catch (ConfigException e) { throw new Failure(422, "CONFIG_INVALID", "Organization policy rejects the current process bindings"); }
        return loaded;
    }

    private static JsonNode parent(ObjectNode document, String pointer) {
        if (pointer.isEmpty()) { return document; }
        JsonNode current = document;
        for (String segment : pointer.substring(1).split("/")) {
            String name = segment.replace("~1", "/").replace("~0", "~");
            if (current instanceof ObjectNode object) {
                if (!object.has(name) && Set.of("settings", "rateLimits", "logging", "errors", "audit", "file", "server", "auth", "storage").contains(name)) { object.putObject(name); }
                current = object.path(name);
            } else if (current.isArray()) {
                try { current = current.path(Integer.parseInt(name)); } catch (NumberFormatException e) { return com.fasterxml.jackson.databind.node.MissingNode.getInstance(); }
            } else { return com.fasterxml.jackson.databind.node.MissingNode.getInstance(); }
        }
        return current;
    }

    private State state() {
        Path file = reloader.current().config().source().orElseThrow(() ->
                new Failure(503, "CONFIG_SOURCE_UNAVAILABLE", "Policy management needs a regular configuration file"));
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("not a regular file");
            }
            byte[] bytes;
            try (var input = Files.newInputStream(file)) {
                bytes = input.readNBytes(SOURCE_LIMIT + 1);
            }
            if (bytes.length > SOURCE_LIMIT) {
                throw new IOException("config too large");
            }
            JsonNode parsed = (json(file) ? JSON : YAML).readTree(bytes);
            if (!(parsed instanceof ObjectNode document)) {
                throw new IOException("not a mapping");
            }
            RuntimeConfig desired = reloader.previewConfig(document);
            RuntimeConfig active = reloader.current().config();
            // configHash intentionally hashes references, not their resolved values. Include the
            // effective policy and target decisions so a referenced policy value changing in .env
            // invalidates a preview without hashing or exposing resolved credentials.
            String policyState = JSON.valueToTree(List.of(policies(desired.document()), decisions(desired),
                    policies(active.document()), decisions(active))).toString();
            String revision = digest(bytes, desired.configHash() + ":" + active.configHash() + ":" + policyState);
            return new State(file, bytes, document, desired, revision);
        } catch (IOException | ConfigException e) {
            throw new Failure(503, "CONFIG_SOURCE_UNAVAILABLE", "The source configuration is unavailable or invalid");
        }
    }

    private Map<String, Object> status(State state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", state.revision());
        result.put("activeConfigHash", reloader.current().config().configHash());
        result.put("desiredConfigHash", state.desired().configHash());
        result.put("restartRequired", reloader.restartRequired(state.desired()));
        result.put("policies", policies(state.document()));
        result.put("fields", ro.bitboy.mcpgp.runtime.config.SettingRegistry.fields());
        result.put("activeSettings", ro.bitboy.mcpgp.runtime.config.SettingRegistry.describe(reloader.current().config()));
        result.put("desiredSettings", ro.bitboy.mcpgp.runtime.config.SettingRegistry.describe(state.desired()));
        result.put("decisions", decisions(reloader.current().config()));
        return result;
    }

    private List<Map<String, Object>> policies(JsonNode document) {
        List<Map<String, Object>> policies = new ArrayList<>();
        addPolicy(policies, document, "");
        for (String api : reloader.manifest().apis().keySet()) {
            String at = reloader.manifest().apis().size() > 1 ? "/apis/" + api : "";
            if (!at.isEmpty()) {
                addPolicy(policies, document, at);
            }
            addPolicy(policies, document, at + "/instance");
            for (int i = 0; i < document.at(at + "/instances").size(); i++) {
                addPolicy(policies, document, at + "/instances/" + i);
            }
        }
        return policies;
    }

    private static void addPolicy(List<Map<String, Object>> output, JsonNode document, String at) {
        JsonNode policy = document.at(at + "/tools");
        if (!policy.isMissingNode()) {
            output.add(Map.of("path", at + "/tools", "value", policy.deepCopy()));
        }
    }

    private List<Map<String, Object>> decisions(RuntimeConfig config) {
        ToolPolicy policy = new ToolPolicy(reloader.manifest(), config);
        List<JsonNode> tools = new ArrayList<>(reloader.manifest().tools().values());
        reloader.manifest().root().path("resources").path("items").forEach(tools::add);
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode tool : tools) {
            if (Set.of("builtin", "pack").contains(tool.path("kind").asText())) {
                continue;
            }
            if (tool.has("api")) {
                for (var instance : config.instances().getOrDefault(tool.path("api").asText(), List.of())) {
                    var decision = decision(policy.name(tool, instance), policy.deniedBy(tool, instance).orElse(null));
                    decision.put("api", instance.api());
                    decision.put("instance", instance.id());
                    result.add(decision);
                }
            } else {
                result.add(decision(tool.path("name").asText(), policy.deniedBy(tool, null).orElse(null)));
            }
        }
        return result;
    }

    private static Map<String, Object> decision(String name, String source) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool", name);
        result.put("allowed", source == null);
        if (source != null) {
            result.put("source", source);
        }
        return result;
    }

    private static List<Map<String, Object>> difference(List<Map<String, Object>> before, List<Map<String, Object>> after) {
        List<Map<String, Object>> differences = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) {
            if (!before.get(i).equals(after.get(i))) {
                differences.add(Map.of("before", before.get(i), "after", after.get(i)));
            }
        }
        return differences;
    }

    private static List<String> policyWarnings(RuntimeConfig config) {
        return config.warnings().stream().filter(w -> w.startsWith("/tools/") || w.contains("/tools/")).toList();
    }

    private static void expected(JsonNode request, State state) {
        if (!request.path("expectedRevision").isTextual() || !request.path("expectedRevision").asText().equals(state.revision())) {
            throw conflict();
        }
    }

    private static Failure conflict() {
        return new Failure(409, "CONFIG_REVISION_CONFLICT", "Configuration changed; read status and create a fresh preview");
    }

    private static Failure invalid(String message) {
        return new Failure(400, "MANAGEMENT_REQUEST_INVALID", message);
    }

    private static void fields(JsonNode object, Set<String> allowed) {
        if (object == null || !object.isObject()) {
            throw invalid("Expected a JSON object");
        }
        object.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) {
                throw invalid("Unexpected request field");
            }
        });
    }

    public void audit(String action, String actor, String outcome, String code, String oldRevision, String newRevision, List<String> paths) {
        var audit = reloader.current().audit();
        ObjectNode event = audit.event("config.management", outcome, Ids.correlation());
        var who = event.putObject("actor").put("kind", actor == null ? "client" : "user").put("auth", "management");
        if (actor != null) {
            who.put("id", actor);
        }
        if (code != null) {
            event.put("code", code);
        }
        ObjectNode details = event.putObject("management").put("action", action);
        if (oldRevision != null) { details.put("oldRevision", oldRevision); }
        if (newRevision != null) { details.put("newRevision", newRevision); }
        paths.forEach(details.putArray("paths")::add);
        event.put("configHash", reloader.current().config().configHash());
        audit.write(event);
    }

    private static boolean json(Path file) {
        return file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json");
    }

    private static String digest(byte[] bytes, String effective) {
        try {
            var hash = java.security.MessageDigest.getInstance("SHA-256");
            hash.update(bytes);
            hash.update((byte) 0);
            hash.update(effective.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> replace(Path file, byte[] expected, byte[] candidate) throws IOException {
        Path absolute = file.toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), ".mcpgp-policy-", ".tmp");
        boolean replaced = false;
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(candidate);
                while (buffer.hasRemaining()) { output.write(buffer); }
                output.force(true);
            }
            try {
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS));
            } catch (UnsupportedOperationException e) {
                // On non-POSIX filesystems, createTempFile uses the platform's default ACLs.
            }
            byte[] latest;
            try (var input = Files.newInputStream(file)) {
                latest = input.readNBytes(expected.length + 1);
            }
            if (Files.isSymbolicLink(file) || !java.util.Arrays.equals(expected, latest)) {
                throw conflict();
            }
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            replaced = true;
            try (FileChannel directory = FileChannel.open(absolute.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
                return List.of();
            } catch (IOException | UnsupportedOperationException e) {
                return List.of("Config replaced; directory sync was unavailable, so crash durability is uncertain.");
            }
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException e) {
                if (!replaced) { throw e; }
            }
        }
    }
}
