package ro.bitboy.mcpgp.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.core.scaffold.Scaffold;
import ro.bitboy.mcpgp.core.scaffold.SpecFiles;
import ro.bitboy.mcpgp.platform.api.ApiException;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Servers from an API's spec (spec/platform-api.md §2.1): a new server around an uploaded OpenAPI or
 * Swagger document (one file, or several that {@code $ref} each other), built like
 * {@code mcpgp init --from-spec}; and a new or replaced contract version of an existing server.
 * Problems with the upload (missing files, references to URLs, versions the compiler doesn't read,
 * several possible main files) are answered before anything is created.
 */
public final class FromSpec {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Store store;
    private final Servers servers;
    private final Edits edits;

    public FromSpec(Store store, Servers servers, Edits edits) {
        this.store = store;
        this.servers = servers;
        this.edits = edits;
    }

    /** What the upload holds, as the page shows it before creating; refuses an upload that can't be used. */
    public record Upload(Map<String, byte[]> files, SpecFiles.Analysis analysis, List<SpecFiles.Move> moves) {
    }

    public Upload read(byte[] body, String contentType, String main) {
        return read(Projects.files(body, contentType), main);
    }

    /** A spec fetched from its address: one file, named after it. */
    public Upload read(SpecFetch.Fetched fetched) {
        return read(Map.of(fetched.name(), fetched.body()), null);
    }

    private Upload read(Map<String, byte[]> uploaded, String main) {
        String chosen = main == null || main.isBlank() ? null : main;
        SpecFiles.Placed placed = SpecFiles.place(SpecFiles.specFiles(uploaded), chosen);
        SpecFiles.Analysis analysis = SpecFiles.analyze(placed.files(), chosen);
        if (!analysis.ok()) {
            throw problems(analysis);
        }
        return new Upload(placed.files(), analysis, placed.moves());
    }

    static ApiException problems(SpecFiles.Analysis a) {
        Map<String, Object> details = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>(a.problems());
        if (a.main() == null && !a.candidates().isEmpty()) {
            details.put("candidates", a.candidates());
            lines.add("several files could be the API's spec; say which one with main (" + String.join(", ", a.candidates()) + ")");
        }
        if (!a.missing().isEmpty()) {
            details.put("missing", a.missing().stream().map(FromSpec::ref).toList());
            a.missing().forEach(m -> lines.add(m.target() + " is missing (referenced from " + m.from() + " at " + m.pointer() + "); add it to the upload"));
        }
        if (!a.broken().isEmpty()) {
            details.put("broken", a.broken().stream().map(FromSpec::ref).toList());
            a.broken().forEach(b -> lines.add(b.from() + " refers to " + b.target() + " (at " + b.pointer() + "), which isn't there"));
        }
        if (!a.remote().isEmpty()) {
            details.put("remote", a.remote().stream().map(FromSpec::ref).toList());
            a.remote().forEach(r -> lines.add(r.from() + " refers to " + r.target() + " (at " + r.pointer()
                    + "); the platform doesn't fetch URLs: add that file to the upload and refer to it by its path"));
        }
        details.put("problems", lines);
        return new ApiException(422, "SPEC_PROBLEMS", lines.isEmpty() ? "the spec can't be used" : String.join("; ", lines), details);
    }

    private static Map<String, String> ref(SpecFiles.Ref r) {
        return Map.of("file", r.target(), "from", r.from(), "pointer", r.pointer());
    }

    /** The answer of a dry run, and the start of a created server's answer. */
    public record Plan(String id, String title, String api, String version, Map<String, byte[]> project, int tools, List<Diagnostic> diagnostics) {
    }

    /** The project a new server would get: its definition, the spec under {@code contracts/}, smoke tests. */
    public Plan plan(Upload upload, String id, String title, String version) {
        return plan(upload, id, title, version, null, Map.of());
    }

    /**
     * @param collisions {@code method-path}: tools whose names clash are named from their method and path
     *                   ({@code naming.collisions} in the definition); null: a clash is answered with suggestions
     * @param renames    names chosen for clashing operations, by operation key ({@code api:PUT:/tests/{identifier}}):
     *                   the name before the prefix, saved as a rule per operation
     */
    public Plan plan(Upload upload, String id, String title, String version, String collisions, Map<String, String> renames) {
        if (collisions != null && !collisions.equals("method-path")) {
            throw ApiException.validation("collisions: method-path, or leave it out");
        }
        SpecFiles.Analysis a = upload.analysis();
        String specTitle = title != null && !title.isBlank() ? title.strip() : a.title() == null || a.title().isEmpty() ? "My API" : a.title();
        String api = Scaffold.apiId(specTitle);
        String server = id != null && !id.isBlank() ? id.strip() : api.replace('_', '-') + "-mcp";
        if (!server.matches("[a-z0-9][a-z0-9-]{1,62}")) {
            throw ApiException.validation("id: lowercase letters, digits and '-', 2–63 characters");
        }
        String contractVersion = version != null && !version.isBlank() ? version.strip()
                : a.version() == null || a.version().isEmpty() ? "1.0" : a.version();
        Map<String, byte[]> project = new LinkedHashMap<>(SpecFiles.under("contracts/", upload.files(), a));
        String definitionFile = server + ".mcpgp.yaml";
        String text = Scaffold.definition(server, specTitle, api, contractVersion, "contracts/" + a.main()) + choices(collisions, renames);
        project.put(definitionFile, text.getBytes(StandardCharsets.UTF_8));
        Compiler.Result result = compile(project, definitionFile);
        if (result.manifest().isEmpty() && collisions == null && result.diagnostics().stream()
                .filter(d -> d.level() == Diagnostic.Level.ERROR).allMatch(d -> d.code().equals(ro.bitboy.mcpgp.core.diag.Codes.NAME_COLLISION))) {
            throw clashes(project, definitionFile, text, api, result);
        }
        if (result.manifest().isEmpty()) {
            throw new ApiException(422, "VALIDATION", "the spec loads, but the server made from it doesn't compile: "
                    + String.join("; ", result.diagnostics().stream().filter(d -> d.level() == Diagnostic.Level.ERROR).map(Diagnostic::message).toList()),
                    Map.of("diagnostics", ro.bitboy.mcpgp.platform.api.ServersApi.diagnostics(result.diagnostics())));
        }
        project.put("tests/smoke.yaml", Scaffold.smoke(result.manifest().get()).getBytes(StandardCharsets.UTF_8));
        int tools = (int) java.util.stream.StreamSupport.stream(result.manifest().get().path("tools").spliterator(), false)
                .filter(t -> t.path("kind").asText().equals("api")).count();
        return new Plan(server, specTitle, api, contractVersion, project, tools, result.diagnostics());
    }

    /** What the user chose for clashing names, as definition text: the collisions rule, or a rule per renamed operation. */
    private static String choices(String collisions, Map<String, String> renames) {
        StringBuilder yaml = new StringBuilder();
        if (collisions != null) {
            yaml.append("\n# tools whose names clash are named from their method and path (naming.md §6)\nnaming:\n  collisions: ").append(collisions).append('\n');
        }
        if (!renames.isEmpty()) {
            yaml.append("\n# names chosen when two operations would have had the same tool name\noverrides:\n  rules:\n");
            for (Map.Entry<String, String> r : new java.util.TreeMap<>(renames).entrySet()) {
                String[] key = r.getKey().split(":", 3);
                if (key.length != 3 || !key[1].matches("[A-Z]+") || !key[2].startsWith("/")) {
                    throw ApiException.validation("renames: keys are operation keys, api:METHOD:/path");
                }
                if (!r.getValue().matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
                    throw ApiException.validation("renames: " + r.getValue() + ": letters, digits and _, starting with a letter");
                }
                String ruleId = ("ui:" + key[1] + ":" + key[2]).replaceAll("[^A-Za-z0-9:._-]+", "_");
                yaml.append("    - id: ").append(quoted(ruleId)).append('\n')
                        .append("      match: { method: ").append(key[1]).append(", path: ").append(quoted(key[2])).append(" }\n")
                        .append("      name: { set: ").append(r.getValue()).append(" }\n");
            }
        }
        return yaml.toString();
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Operations that would get the same tool name: each later one with the name it would get from its
     * method and path, for the user to accept, edit, or make the rule ({@code NAME_COLLISIONS}).
     */
    private static ApiException clashes(Map<String, byte[]> project, String definitionFile, String text, String api, Compiler.Result first) {
        Map<String, byte[]> resolved = new LinkedHashMap<>(project);
        resolved.put(definitionFile, (text + choices("method-path", Map.of())).getBytes(StandardCharsets.UTF_8));
        Compiler.Result again = compile(resolved, definitionFile);
        List<Map<String, Object>> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (again.manifest().isPresent()) {
            for (JsonNode tool : again.manifest().get().path("tools")) {
                List<JsonNode> steps = new ArrayList<>();
                tool.at("/provenance/name").forEach(steps::add);
                int at = -1;
                for (int i = 0; i < steps.size(); i++) {
                    if (steps.get(i).path("step").asText().equals("collision")) {
                        at = i;
                    }
                }
                String key = tool.at("/operation/key").asText();
                boolean contentType = steps.stream().anyMatch(s -> s.path("step").asText().equals("contentTypeSuffix"));
                if (at < 1 || contentType || !seen.add(key)) {
                    continue; // a content type's tool follows its operation's name
                }
                String[] parts = key.split(":", 3);
                String suggested = tool.path("name").asText();
                Map<String, Object> clash = new LinkedHashMap<>();
                clash.put("operation", key);
                clash.put("method", parts.length == 3 ? parts[1] : "");
                clash.put("path", parts.length == 3 ? parts[2] : "");
                clash.put("operationId", tool.at("/operation/operationId").asText(null));
                clash.put("name", steps.get(at - 1).path("value").asText());
                clash.put("suggested", suggested);
                clash.put("base", suggested.startsWith(api + "_") ? suggested.substring(api.length() + 1) : suggested);
                list.add(clash);
            }
        }
        List<String> lines = first.diagnostics().stream().filter(d -> d.level() == Diagnostic.Level.ERROR).map(Diagnostic::message).toList();
        return new ApiException(422, "NAME_COLLISIONS", "operations would get the same tool name: " + String.join("; ", lines),
                Map.of("collisions", list, "problems", lines));
    }

    public Model.Server create(Plan plan, String message, Access.Principal who) {
        return servers.create(plan.id(), plan.title(), plan.project(), message != null && !message.isBlank() ? message
                : "from " + plan.project().keySet().stream().filter(p -> p.startsWith("contracts/")).findFirst().orElse("a spec"), who);
    }

    /** A contract version for a server's API: what changes in its definition, and the files it adds. */
    /**
     * @param source    the address the spec was fetched from, recorded in the definition; null for an upload
     * @param unchanged the version exists with exactly these files (and source): saving would change nothing
     */
    public record ContractChange(String api, String version, String spec, boolean replaces, List<YamlEdit.Change> changes, Map<String, byte[]> files,
                                 String source, boolean unchanged) {
    }

    /**
     * Adds the uploaded spec as a contract version of {@code api} (the server's only API when null), or
     * replaces that version's spec when it exists. The files go under {@code contracts/<version>/}.
     */
    public ContractChange contract(String id, Upload upload, String api, String version, boolean makeDefault) {
        return contract(id, upload, api, version, makeDefault, null);
    }

    /** The address the spec of an API's contract version was fetched from ({@code source}); empty when it was uploaded. */
    public java.util.Optional<String> source(String id, String api, String version) {
        Model.Server server = servers.get(id);
        if (server.latest() == null) {
            return java.util.Optional.empty();
        }
        JsonNode definition;
        try {
            Model.Version files = servers.files(server.latest());
            definition = YAML.readTree(store.blob(files.files().get(files.root())));
        } catch (IOException e) {
            throw ApiException.validation("the server's definition can't be read");
        }
        List<JsonNode> apis = new ArrayList<>();
        if (definition.has("api")) {
            apis.add(definition.get("api"));
        } else {
            definition.path("apis").forEach(apis::add);
        }
        for (JsonNode a : apis) {
            if (api == null || api.isBlank() || a.path("id").asText().equals(api)) {
                for (JsonNode c : a.path("contracts")) {
                    if (c.path("version").asText().equals(version) && c.hasNonNull("source")) {
                        return java.util.Optional.of(c.get("source").asText());
                    }
                }
            }
        }
        return java.util.Optional.empty();
    }

    /** @param source the spec's address when it was fetched ({@code apis[].contracts[].source}); null for an upload */
    public ContractChange contract(String id, Upload upload, String api, String version, boolean makeDefault, String source) {
        Model.Server server = servers.get(id);
        if (server.latest() == null) {
            throw new ApiException(409, "CONFLICT", "server " + id + " has no version yet; upload one");
        }
        Model.Version files = servers.files(server.latest());
        JsonNode definition;
        try {
            definition = YAML.readTree(store.blob(files.files().get(files.root())));
        } catch (IOException e) {
            throw ApiException.validation("the server's definition can't be read");
        }
        boolean single = definition.has("api");
        List<JsonNode> apis = new ArrayList<>();
        if (single) {
            apis.add(definition.get("api"));
        } else {
            definition.path("apis").forEach(apis::add);
        }
        if (apis.isEmpty()) {
            throw ApiException.validation("the server's definition has no API (a variant gets its contracts from what it extends)");
        }
        JsonNode target;
        if (api == null || api.isBlank()) {
            if (apis.size() > 1) {
                throw ApiException.validation("api: the server has several APIs (" + String.join(", ", apis.stream().map(a -> a.path("id").asText()).toList())
                        + "); say which one the spec belongs to");
            }
            target = apis.get(0);
        } else {
            target = apis.stream().filter(a -> a.path("id").asText().equals(api)).findFirst()
                    .orElseThrow(() -> ApiException.validation("api: no API " + api + " in the server"));
        }
        String apiId = target.path("id").asText();
        SpecFiles.Analysis a = upload.analysis();
        String contractVersion = version != null && !version.isBlank() ? version.strip()
                : a.version() == null || a.version().isEmpty() ? null : a.version();
        if (contractVersion == null) {
            throw ApiException.validation("version: the spec has no info.version; say which contract version it is");
        }
        if (!contractVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,40}")) {
            throw ApiException.validation("version: letters, digits, '.', '_' and '-'");
        }
        String base = single ? "/api" : "/apis/[id=" + apiId + "]";
        String folder = "contracts/" + contractVersion + "/";
        String spec = folder + a.main();
        Map<String, byte[]> added = SpecFiles.under(folder, upload.files(), a);
        JsonNode existing = null;
        for (JsonNode c : target.path("contracts")) {
            if (c.path("version").asText().equals(contractVersion)) {
                existing = c;
            }
        }
        boolean exists = existing != null;
        List<YamlEdit.Change> changes = new ArrayList<>();
        String at = base + "/contracts/[version=" + contractVersion + "]";
        boolean unchanged = exists && existing.path("spec").asText().equals(spec) && java.util.Objects.equals(existing.path("source").asText(null), source);
        if (exists) {
            changes.add(new YamlEdit.Change("set", at + "/spec", JsonNodeFactory.instance.textNode(spec)));
            if (source != null) {
                changes.add(new YamlEdit.Change("set", at + "/source", JsonNodeFactory.instance.textNode(source)));
            } else if (existing.has("source")) {
                changes.add(new YamlEdit.Change("remove", at + "/source", null)); // uploaded by hand: no longer from that address
            }
        } else {
            ObjectNode contract = JsonNodeFactory.instance.objectNode().put("version", contractVersion).put("spec", spec);
            if (source != null) {
                contract.put("source", source);
            }
            changes.add(new YamlEdit.Change("set", at, contract));
        }
        for (Map.Entry<String, byte[]> file : SpecFiles.under(folder, upload.files(), a).entrySet()) {
            String blob = files.files().get(file.getKey());
            unchanged &= blob != null && java.util.Arrays.equals(store.blob(blob), file.getValue());
        }
        if (makeDefault) {
            for (JsonNode c : target.path("contracts")) {
                if (c.path("default").asBoolean(false) && !c.path("version").asText().equals(contractVersion)) {
                    changes.add(new YamlEdit.Change("remove", base + "/contracts/[version=" + c.path("version").asText() + "]/default", null));
                }
            }
            changes.add(new YamlEdit.Change("set", at + "/default", JsonNodeFactory.instance.booleanNode(true)));
        }
        unchanged = unchanged && (!makeDefault || existing.path("default").asBoolean(false));
        return new ContractChange(apiId, contractVersion, spec, exists, changes, added, source, unchanged);
    }

    private static Compiler.Result compile(Map<String, byte[]> project, String definition) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("mcpgp-from-spec-");
            for (Map.Entry<String, byte[]> f : project.entrySet()) {
                Path file = dir.resolve(f.getKey()).normalize();
                if (!file.startsWith(dir)) {
                    throw ApiException.validation("invalid path " + f.getKey());
                }
                Files.createDirectories(file.getParent());
                Files.write(file, f.getValue());
            }
            return new Compiler(new Schemas()).compile(dir.resolve(definition), Compiler.Options.defaults());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (dir != null) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException e) {
                    // a temporary folder
                }
            }
        }
    }
}
