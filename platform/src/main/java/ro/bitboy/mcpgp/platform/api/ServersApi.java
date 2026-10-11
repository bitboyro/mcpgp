package ro.bitboy.mcpgp.platform.api;

import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.core.manifest.ManifestWriter;
import ro.bitboy.mcpgp.packager.ExampleConfig;
import ro.bitboy.mcpgp.platform.service.Edits;
import ro.bitboy.mcpgp.platform.service.YamlEdit;
import ro.bitboy.mcpgp.platform.service.Access;
import ro.bitboy.mcpgp.platform.service.Compilation;
import ro.bitboy.mcpgp.platform.service.Servers;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Servers and versions (spec/platform-api.md §2.1). */
@RestController
@RequestMapping("/api/v1/servers")
public class ServersApi {

    private final Store store;
    private final Servers servers;
    private final Compilation compilation;
    private final ro.bitboy.mcpgp.platform.PlatformConfig config;
    private final ro.bitboy.mcpgp.platform.service.Guardrails guardrails;
    private final Edits edits;
    private final ro.bitboy.mcpgp.platform.service.Trials trials;

    public ServersApi(Store store, Servers servers, Compilation compilation, ro.bitboy.mcpgp.platform.PlatformConfig config,
                      ro.bitboy.mcpgp.platform.service.Guardrails guardrails, Edits edits, ro.bitboy.mcpgp.platform.service.Trials trials) {
        this.edits = edits;
        this.trials = trials;
        this.store = store;
        this.servers = servers;
        this.compilation = compilation;
        this.config = config;
        this.guardrails = guardrails;
    }

    static Access.Principal who(HttpServletRequest request) {
        return (Access.Principal) request.getAttribute(AuthFilter.PRINCIPAL);
    }

    @GetMapping
    public ResponseEntity<byte[]> list(HttpServletRequest request, @RequestParam(defaultValue = "false") boolean archived) {
        who(request).require("servers:read");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Model.Server server : store.servers()) {
            if (server.archived() == archived) {
                items.add(summary(server));
            }
        }
        return Json.ok(Map.of("items", items));
    }

    @PostMapping
    public ResponseEntity<byte[]> create(HttpServletRequest request, @RequestParam String id, @RequestParam(required = false) String title,
                                         @RequestParam(required = false) String message,
                                         @RequestHeader(value = "Content-Type", required = false) String contentType,
                                         @RequestBody(required = false) byte[] body) {
        Access.Principal who = who(request);
        who.require("servers:write");
        guardrails.quota(who, "versions");
        Model.Server server = servers.create(id, title, body == null ? new byte[0] : body, contentType, message, who);
        return withEtag(Json.status(201, detail(server)), server);
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> get(HttpServletRequest request, @PathVariable String id) {
        who(request).require("servers:read");
        Model.Server server = servers.get(id);
        return withEtag(Json.ok(detail(server)), server);
    }

    @PostMapping("/{id}/versions")
    public ResponseEntity<byte[]> addVersion(HttpServletRequest request, @PathVariable String id, @RequestParam(required = false) String message,
                                             @RequestHeader(value = "Content-Type", required = false) String contentType,
                                             @RequestBody(required = false) byte[] body) {
        Access.Principal who = who(request);
        who.require("servers:write");
        guardrails.quota(who, "versions");
        Model.Server before = servers.get(id);
        Model.Server server = servers.addVersion(id, body == null ? new byte[0] : body, contentType, message, who);
        return withEtag(Json.status(server.revision() == before.revision() ? 200 : 201, detail(server)), server);
    }

    @GetMapping("/{id}/versions/{version}")
    public ResponseEntity<byte[]> version(HttpServletRequest request, @PathVariable String id, @PathVariable String version) {
        who(request).require("servers:read");
        Model.Server server = servers.get(id);
        Model.VersionRef ref = servers.version(server, version);
        Model.Version files = servers.files(ref);
        Map<String, Object> body = new LinkedHashMap<>(versionRef(ref));
        body.put("root", files.root());
        List<Map<String, Object>> list = new ArrayList<>();
        files.files().forEach((path, blob) -> list.add(Map.of("path", path, "sha256", blob)));
        body.put("files", list);
        return Json.ok(body);
    }

    @GetMapping("/{id}/versions/{version}/files/{*path}")
    public ResponseEntity<byte[]> file(HttpServletRequest request, @PathVariable String id, @PathVariable String version,
                                       @PathVariable String path) {
        who(request).require("servers:read");
        Model.Version files = servers.files(servers.version(servers.get(id), version));
        String blob = files.files().get(path.startsWith("/") ? path.substring(1) : path);
        if (blob == null) {
            throw ApiException.notFound("file " + path);
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(store.blob(blob));
    }

    @PostMapping("/{id}/versions/{version}/validate")
    public ResponseEntity<byte[]> validate(HttpServletRequest request, @PathVariable String id, @PathVariable String version) {
        who(request).require("servers:read");
        Model.VersionRef ref = servers.version(servers.get(id), version);
        Compiler.Result result = compilation.compile(servers.files(ref),
                new ManifestWriter.Build(Instant.parse(ref.created()), Compiler.VERSION, null, null, null));
        return Json.ok(Map.of("ok", result.ok(), "diagnostics", diagnostics(result.diagnostics())));
    }

    @GetMapping("/{id}/versions/{version}/manifest")
    public ResponseEntity<byte[]> manifest(HttpServletRequest request, @PathVariable String id, @PathVariable String version) {
        who(request).require("servers:read");
        Model.VersionRef ref = servers.version(servers.get(id), version);
        Compilation.Preview preview = compilation.preview(servers.files(ref), ref.created());
        if (preview.manifest() == null) {
            throw new ApiException(422, "VALIDATION", "version " + ref.number() + " does not compile",
                    Map.of("diagnostics", diagnostics(preview.result().diagnostics())));
        }
        return Json.ok(preview.manifest());
    }

    /** Every operation of the version's contracts: a tool, a resource, left out by {@code select} (and by what), or hidden by a rule. */
    @GetMapping("/{id}/versions/{version}/operations")
    public ResponseEntity<byte[]> operations(HttpServletRequest request, @PathVariable String id, @PathVariable String version) {
        return derived(request, id, version, compilation::operations);
    }

    /** The version's naming: the policy it extends, its own settings, the effective policy and the lock file's entries. */
    @GetMapping("/{id}/versions/{version}/naming")
    public ResponseEntity<byte[]> naming(HttpServletRequest request, @PathVariable String id, @PathVariable String version) {
        return derived(request, id, version, compilation::naming);
    }

    /**
     * Calls a tool of the version against a mock of its API (no real system is contacted):
     * {@code {tool, arguments}} → {@code {result | error, requests, leftOut}}.
     */
    @PostMapping("/{id}/versions/{version}/try")
    public ResponseEntity<byte[]> tryTool(HttpServletRequest request, @PathVariable String id, @PathVariable String version,
                                          @RequestBody(required = false) byte[] body) {
        who(request).require("builds:run");
        if (body != null && body.length > 64 * 1024) {
            throw ApiException.validation("the arguments are larger than 64 KB");
        }
        JsonNode json = Json.parse(body);
        String tool = json.path("tool").asText("");
        if (tool.isEmpty()) {
            throw ApiException.validation("tool: the tool to call");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = json.path("arguments").isObject()
                ? new com.fasterxml.jackson.databind.ObjectMapper().convertValue(json.path("arguments"), Map.class) : Map.of();
        Model.VersionRef ref = servers.version(servers.get(id), version);
        return Json.ok(trials.call(servers.files(ref), ref.created(), tool, arguments));
    }

    /** The definition as data: the root file as written, and the result with what it extends and includes merged in. */
    @GetMapping("/{id}/versions/{version}/definition")
    public ResponseEntity<byte[]> definition(HttpServletRequest request, @PathVariable String id, @PathVariable String version) {
        return derived(request, id, version, compilation::definition);
    }

    /** What a build of the version will contain: formats, files, the generated docs, warnings; signed as the platform signs. */
    @GetMapping("/{id}/versions/{version}/package")
    public ResponseEntity<byte[]> packagePreview(HttpServletRequest request, @PathVariable String id, @PathVariable String version) {
        boolean signed = config.signingKey() != null;
        return derived(request, id, version, (files, created) -> {
            com.fasterxml.jackson.databind.node.ObjectNode preview = compilation.packagePreview(files, created, signed);
            return preview == null ? null : preview.put("signed", signed);
        });
    }

    private ResponseEntity<byte[]> derived(HttpServletRequest request, String id, String version,
                                           java.util.function.BiFunction<Model.Version, String, com.fasterxml.jackson.databind.node.ObjectNode> from) {
        who(request).require("servers:read");
        Model.VersionRef ref = servers.version(servers.get(id), version);
        com.fasterxml.jackson.databind.node.ObjectNode value = from.apply(servers.files(ref), ref.created());
        if (value == null) {
            Compiler.Result result = compilation.compile(servers.files(ref), new ManifestWriter.Build(Instant.parse(ref.created()), Compiler.VERSION, null, null, null));
            throw new ApiException(422, "VALIDATION", "version " + ref.number() + " does not compile",
                    Map.of("diagnostics", diagnostics(result.diagnostics())));
        }
        value.put("version", ref.number());
        return Json.ok(value);
    }

    /** {@code {id, title?}}: an independent copy of the latest version. */
    /**
     * Changes made on the server's pages: {@code {base, changes: [{op, path, value}], message?, dryRun?}}.
     * With {@code dryRun} the answer is the edited definition and its manifest (or diagnostics) and
     * nothing is saved; otherwise the edit becomes the next version, if it compiles.
     */
    @PostMapping("/{id}/edits")
    public ResponseEntity<byte[]> edit(HttpServletRequest request, @PathVariable String id, @RequestBody(required = false) byte[] body) {
        Access.Principal who = who(request);
        who.require("servers:write");
        JsonNode json = Json.parse(body);
        if (!json.path("base").canConvertToInt()) {
            throw ApiException.validation("base: the version number the changes were made on");
        }
        int base = json.path("base").asInt();
        List<YamlEdit.Change> changes = Edits.changes(json);
        if (json.path("dryRun").asBoolean(false)) {
            Edits.Result result = edits.preview(id, base, changes);
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("base", base);
            answer.put("digest", "sha256:" + result.version().digest());
            answer.put("definition", result.definition());
            if (result.compiles()) {
                JsonNode manifest = Json.parse(result.preview().manifest().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                answer.put("manifest", manifest);
                answer.put("exampleConfig", ExampleConfig.yaml(manifest));
                if (json.path("operations").asBoolean(false)) {
                    answer.put("operations", compilation.operations(result.version(), Instant.now().toString()));
                }
            } else {
                answer.put("diagnostics", diagnostics(result.preview().result().diagnostics()));
            }
            return Json.ok(answer);
        }
        guardrails.quota(who, "versions");
        Model.Server server = edits.save(id, base, changes, json.path("message").asText(null), who);
        return withEtag(Json.status(201, detail(server)), server);
    }

    /** The example configuration the package will ship (packaging.md): what the customer fills in. */
    @GetMapping("/{id}/versions/{version}/example-config")
    public ResponseEntity<byte[]> exampleConfig(HttpServletRequest request, @PathVariable String id, @PathVariable String version) {
        who(request).require("servers:read");
        Model.VersionRef ref = servers.version(servers.get(id), version);
        Compilation.Preview preview = compilation.preview(servers.files(ref), ref.created());
        if (preview.manifest() == null) {
            throw new ApiException(422, "VALIDATION", "version " + ref.number() + " does not compile",
                    Map.of("diagnostics", diagnostics(preview.result().diagnostics())));
        }
        String yaml = ExampleConfig.yaml(Json.parse(preview.manifest().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/yaml")).body(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<byte[]> duplicate(HttpServletRequest request, @PathVariable String id, @RequestBody(required = false) byte[] body) {
        Access.Principal who = who(request);
        who.require("servers:write");
        com.fasterxml.jackson.databind.JsonNode json = Json.parse(body);
        Model.Server server = servers.duplicate(id, json.path("id").asText(null), json.path("title").asText(null), who);
        return withEtag(Json.status(201, detail(server)), server);
    }

    /** {@code {id, title?, pin?}}: a server that extends this one and holds only overrides. */
    @PostMapping("/{id}/variants")
    public ResponseEntity<byte[]> variant(HttpServletRequest request, @PathVariable String id, @RequestBody(required = false) byte[] body) {
        Access.Principal who = who(request);
        who.require("servers:write");
        com.fasterxml.jackson.databind.JsonNode json = Json.parse(body);
        Model.Server server = servers.variant(id, json.path("id").asText(null), json.path("title").asText(null),
                json.path("pin").asBoolean(false), config.workspace(), who);
        return withEtag(Json.status(201, detail(server)), server);
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<byte[]> archive(HttpServletRequest request, @PathVariable String id,
                                          @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return archived(request, id, true, ifMatch);
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<byte[]> restore(HttpServletRequest request, @PathVariable String id,
                                          @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return archived(request, id, false, ifMatch);
    }

    private ResponseEntity<byte[]> archived(HttpServletRequest request, String id, boolean archived, String ifMatch) {
        Access.Principal who = who(request);
        who.require("servers:write");
        long expected = -1;
        if (ifMatch != null) {
            try {
                expected = Long.parseLong(ifMatch.replace("\"", "").strip());
            } catch (NumberFormatException e) {
                throw ApiException.validation("If-Match must be an ETag from this API");
            }
        }
        Model.Server server = servers.setArchived(id, archived, expected, who);
        return withEtag(Json.ok(detail(server)), server);
    }

    // ---------------------------------------------------------------------- shapes

    static Map<String, Object> summary(Model.Server server) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", server.id());
        s.put("title", server.title());
        if (server.parent() != null) {
            s.put("parent", server.parent());
        }
        s.put("archived", server.archived());
        s.put("latestVersion", server.latest() == null ? null : server.latest().number());
        s.put("updated", server.updated());
        return s;
    }

    static Map<String, Object> detail(Model.Server server) {
        Map<String, Object> s = summary(server);
        s.put("created", server.created());
        List<Map<String, Object>> versions = new ArrayList<>();
        server.versions().forEach(v -> versions.add(versionRef(v)));
        s.put("versions", versions);
        return s;
    }

    static Map<String, Object> versionRef(Model.VersionRef v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("number", v.number());
        m.put("digest", "sha256:" + v.digest());
        if (v.message() != null) {
            m.put("message", v.message());
        }
        m.put("author", v.author());
        m.put("created", v.created());
        return m;
    }

    public static List<Map<String, Object>> diagnostics(List<Diagnostic> diagnostics) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Diagnostic d : diagnostics) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", d.level().name().toLowerCase());
            m.put("code", d.code());
            m.put("message", d.message());
            if (d.location() != null) {
                m.put("location", d.location().toString());
            }
            list.add(m);
        }
        return list;
    }

    private static ResponseEntity<byte[]> withEtag(ResponseEntity<byte[]> response, Model.Server server) {
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).eTag("\"" + server.revision() + "\"")
                .body(response.getBody());
    }
}
