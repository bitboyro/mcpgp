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
        String chosen = main == null || main.isBlank() ? null : main;
        SpecFiles.Placed placed = SpecFiles.place(SpecFiles.specFiles(Projects.files(body, contentType)), chosen);
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
        project.put(definitionFile, Scaffold.definition(server, specTitle, api, contractVersion, "contracts/" + a.main())
                .getBytes(StandardCharsets.UTF_8));
        Compiler.Result result = compile(project, definitionFile);
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

    public Model.Server create(Plan plan, String message, Access.Principal who) {
        return servers.create(plan.id(), plan.title(), plan.project(), message != null && !message.isBlank() ? message
                : "from " + plan.project().keySet().stream().filter(p -> p.startsWith("contracts/")).findFirst().orElse("a spec"), who);
    }

    /** A contract version for a server's API: what changes in its definition, and the files it adds. */
    public record ContractChange(String api, String version, String spec, boolean replaces, List<YamlEdit.Change> changes, Map<String, byte[]> files) {
    }

    /**
     * Adds the uploaded spec as a contract version of {@code api} (the server's only API when null), or
     * replaces that version's spec when it exists. The files go under {@code contracts/<version>/}.
     */
    public ContractChange contract(String id, Upload upload, String api, String version, boolean makeDefault) {
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
        boolean exists = false;
        for (JsonNode c : target.path("contracts")) {
            if (c.path("version").asText().equals(contractVersion)) {
                exists = true;
            }
        }
        List<YamlEdit.Change> changes = new ArrayList<>();
        String at = base + "/contracts/[version=" + contractVersion + "]";
        if (exists) {
            changes.add(new YamlEdit.Change("set", at + "/spec", JsonNodeFactory.instance.textNode(spec)));
        } else {
            ObjectNode contract = JsonNodeFactory.instance.objectNode().put("version", contractVersion).put("spec", spec);
            changes.add(new YamlEdit.Change("set", at, contract));
        }
        if (makeDefault) {
            for (JsonNode c : target.path("contracts")) {
                if (c.path("default").asBoolean(false) && !c.path("version").asText().equals(contractVersion)) {
                    changes.add(new YamlEdit.Change("remove", base + "/contracts/[version=" + c.path("version").asText() + "]/default", null));
                }
            }
            changes.add(new YamlEdit.Change("set", at + "/default", JsonNodeFactory.instance.booleanNode(true)));
        }
        return new ContractChange(apiId, contractVersion, spec, exists, changes, added);
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
