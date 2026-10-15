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
import ro.bitboy.mcpgp.platform.service.SpecFetch;
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
    private final ro.bitboy.mcpgp.platform.service.FromSpec fromSpec;

    private final SpecFetch specFetch;

    public ServersApi(Store store, Servers servers, Compilation compilation, ro.bitboy.mcpgp.platform.PlatformConfig config,
                      ro.bitboy.mcpgp.platform.service.Guardrails guardrails, Edits edits, ro.bitboy.mcpgp.platform.service.Trials trials,
                      ro.bitboy.mcpgp.platform.service.FromSpec fromSpec) {
        this.fromSpec = fromSpec;
        this.specFetch = new SpecFetch(config.specHosts(), System.getenv());
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

    /**
     * A new server from an API's spec (one file, or several that {@code $ref} each other; JSON
     * {@code {files}} or a zip): the definition, the spec under {@code contracts/} and smoke tests, as
     * {@code mcpgp init --from-spec} makes them. {@code id}, {@code title} and {@code version} default to
     * what the spec says; {@code main} picks the main file when several could be. A dry run answers what
     * would be created; problems with the upload are {@code 422 SPEC_PROBLEMS} with {@code missing},
     * {@code remote} and {@code candidates}.
     */
    @PostMapping("/from-spec")
    public ResponseEntity<byte[]> createFromSpec(HttpServletRequest request, @RequestParam(required = false) String id,
                                                 @RequestParam(required = false) String title, @RequestParam(required = false) String version,
                                                 @RequestParam(required = false) String main, @RequestParam(required = false) String message,
                                                 @RequestParam(defaultValue = "false") boolean dryRun,
                                                 @RequestParam(required = false) String collisions,
                                                 @RequestParam(required = false) String renames,
                                                 @RequestHeader(value = "Content-Type", required = false) String contentType,
                                                 @RequestBody(required = false) byte[] body) {
        Access.Principal who = who(request);
        who.require("servers:write");
        Map<String, String> chosen = new java.util.LinkedHashMap<>();
        if (renames != null && !renames.isBlank()) {
            JsonNode parsed = Json.parse(renames.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!parsed.isObject()) {
                throw ApiException.validation("renames: a JSON object of operation key → name");
            }
            parsed.fields().forEachRemaining(f -> chosen.put(f.getKey(), f.getValue().asText()));
        }
        var upload = fromSpec.read(body == null ? new byte[0] : body, contentType, main);
        var plan = fromSpec.plan(upload, id, title, version, collisions == null || collisions.isBlank() ? null : collisions, chosen);
        if (dryRun) {
            Map<String, Object> answer = new java.util.LinkedHashMap<>();
            answer.put("id", plan.id());
            answer.put("title", plan.title());
            answer.put("api", plan.api());
            answer.put("version", plan.version());
            answer.put("main", upload.analysis().main());
            answer.put("format", upload.analysis().format());
            answer.put("files", List.copyOf(plan.project().keySet()));
            answer.put("moved", upload.moves().stream().map(m -> Map.of("from", m.from(), "to", m.to())).toList());
            answer.put("tools", plan.tools());
            answer.put("exists", store.server(plan.id()).isPresent());
            answer.put("diagnostics", diagnostics(plan.diagnostics()));
            return Json.ok(answer);
        }
        guardrails.quota(who, "versions");
        Model.Server server = fromSpec.create(plan, message, who);
        return withEtag(Json.status(201, detail(server)), server);
    }

    /**
     * A contract version from an uploaded spec, like {@code from-spec}: added to the server's API (or
     * {@code api}), or replacing the spec of that version when it exists; {@code version} defaults to
     * the spec's {@code info.version}, {@code default=true} makes it the default. The files go under
     * {@code contracts/<version>/}; the server gets its next version. A dry run answers whether it
     * compiles and what would change for agents. Instead of a body, {@code url} fetches the spec from
     * an allowed host ({@code specHosts}) and records the address as the version's {@code source};
     * {@code source=true} fetches it again from the address recorded for {@code version}. A spec that
     * is the same as the saved one answers {@code unchanged} and makes no version.
     */
    @PostMapping("/{id}/contracts")
    public ResponseEntity<byte[]> addContract(HttpServletRequest request, @PathVariable String id, @RequestParam(required = false) String api,
                                              @RequestParam(required = false) String version, @RequestParam(required = false) String main,
                                              @RequestParam(name = "default", defaultValue = "false") boolean makeDefault,
                                              @RequestParam(required = false) String message, @RequestParam(defaultValue = "false") boolean dryRun,
                                              @RequestParam(required = false) String url,
                                              @RequestParam(name = "source", defaultValue = "false") boolean fromSource,
                                              @RequestHeader(value = "Content-Type", required = false) String contentType,
                                              @RequestBody(required = false) byte[] body) {
        Access.Principal who = who(request);
        who.require("servers:write");
        if (fromSource) {
            if (version == null || version.isBlank()) {
                throw ApiException.validation("version: say which version's recorded address to fetch again");
            }
            url = fromSpec.source(id, api, version).orElseThrow(() -> ApiException.validation(
                    "version " + version + " wasn't fetched from an address; upload its spec, or give url"));
        }
        boolean fetching = url != null && !url.isBlank();
        if (fetching && body != null && body.length > 0) {
            throw ApiException.validation("url: fetch the spec or upload it, not both");
        }
        SpecFetch.Fetched fetched = fetching ? specFetch.fetch(url) : null;
        var upload = fetching ? fromSpec.read(fetched) : fromSpec.read(body == null ? new byte[0] : body, contentType, main);
        var change = fromSpec.contract(id, upload, api, version, makeDefault, fetching ? url.strip() : null);
        Model.Server current = servers.get(id);
        int base = current.latest().number();
        if (dryRun) {
            Edits.Result result = edits.preview(id, base, change.changes(), change.files());
            Map<String, Object> answer = new java.util.LinkedHashMap<>();
            answer.put("api", change.api());
            answer.put("version", change.version());
            answer.put("spec", change.spec());
            answer.put("replaces", change.replaces());
            answer.put("moved", upload.moves().stream().map(m -> Map.of("from", m.from(), "to", m.to())).toList());
            answer.put("compiles", result.compiles());
            answer.put("unchanged", change.unchanged());
            if (fetched != null) {
                answer.put("source", change.source());
                answer.put("fetched", Map.of("url", fetched.url(), "file", fetched.name(), "bytes", fetched.body().length));
            }
            if (result.compiles() && !change.unchanged()) {
                // what agents would notice, as on the Changes page
                var diff = ro.bitboy.mcpgp.core.diff.ManifestDiff.compare(manifestOf(current, current.latest()),
                        Json.parse(result.preview().manifest().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                answer.put("summary", ro.bitboy.mcpgp.core.diff.DiffReport.summary(diff));
                answer.put("breaking", diff.breaking());
                answer.put("changes", changeList(diff));
            }
            // a preview from the cache has no compiler result; its manifest lists the diagnostics
            answer.put("diagnostics", result.preview().result() != null ? diagnostics(result.preview().result().diagnostics())
                    : Json.parse(result.preview().manifest().getBytes(java.nio.charset.StandardCharsets.UTF_8)).path("diagnostics"));
            return Json.ok(answer);
        }
        if (change.unchanged()) {
            return withEtag(Json.ok(detail(current)), current); // the same spec: no new version
        }
        guardrails.quota(who, "versions");
        String text = message != null && !message.isBlank() ? message
                : (change.replaces() ? "Replace the spec of " : "Add contract ") + change.api() + "@" + change.version()
                + (fetching ? " from " + change.source() : "");
        Model.Server server = edits.save(id, base, change.changes(), change.files(), text, who);
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

    /**
     * What changed between two versions of a server, as {@code mcpgp diff} reports it (cli.md §3.5):
     * {@code from} and {@code to} are version numbers, {@code latest}, or {@code released} (the version
     * of the server's last released build). Default: from the last release (or the version before the
     * latest), to the latest.
     */
    @GetMapping("/{id}/diff")
    public ResponseEntity<byte[]> diff(HttpServletRequest request, @PathVariable String id,
                                       @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        who(request).require("servers:read");
        Model.Server server = servers.get(id);
        if (server.latest() == null) {
            throw ApiException.notFound("version");
        }
        Model.VersionRef released = store.builds().stream().filter(b -> b.server().equals(id) && b.released())
                .max(java.util.Comparator.comparing(Model.Build::created))
                .flatMap(b -> server.versions().stream().filter(v -> v.number() == b.version()).findFirst()).orElse(null);
        Model.VersionRef after = to == null || to.isBlank() ? server.latest() : endpoint(server, to, released);
        Model.VersionRef before;
        if (from == null || from.isBlank()) {
            before = released != null ? released : server.versions().stream().filter(v -> v.number() == after.number() - 1).findFirst().orElse(after);
        } else {
            before = endpoint(server, from, released);
        }
        JsonNode beforeManifest = manifestOf(server, before);
        JsonNode afterManifest = manifestOf(server, after);
        ro.bitboy.mcpgp.core.diff.ManifestDiff.Result result = ro.bitboy.mcpgp.core.diff.ManifestDiff.compare(beforeManifest, afterManifest);
        Map<String, Object> answer = new java.util.LinkedHashMap<>();
        answer.put("from", Map.of("version", before.number(), "serverVersion", result.from(), "released", released != null && released.number() == before.number()));
        answer.put("to", Map.of("version", after.number(), "serverVersion", result.to()));
        answer.put("released", released == null ? null : released.number());
        answer.put("summary", ro.bitboy.mcpgp.core.diff.DiffReport.summary(result));
        answer.put("breaking", result.breaking());
        answer.put("changes", changeList(result));
        answer.put("markdown", ro.bitboy.mcpgp.core.diff.DiffReport.markdown(id, result));
        return Json.ok(answer);
    }

    private static List<Map<String, Object>> changeList(ro.bitboy.mcpgp.core.diff.ManifestDiff.Result result) {
        return result.changes().stream().map(c -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("impact", c.impact().name().toLowerCase(java.util.Locale.ROOT));
            m.put("tool", c.tool());
            m.put("kind", c.kind());
            m.put("text", c.text());
            return m;
        }).toList();
    }

    private Model.VersionRef endpoint(Model.Server server, String which, Model.VersionRef released) {
        if (which.equals("released")) {
            if (released == null) {
                throw ApiException.notFound("a released build of " + server.id());
            }
            return released;
        }
        return servers.version(server, which);
    }

    private JsonNode manifestOf(Model.Server server, Model.VersionRef ref) {
        Compilation.Preview preview = compilation.preview(servers.files(ref), ref.created());
        if (preview.manifest() == null) {
            throw new ApiException(422, "VALIDATION", "version " + ref.number() + " of " + server.id() + " doesn't compile",
                    Map.of("diagnostics", diagnostics(preview.result().diagnostics())));
        }
        return Json.parse(preview.manifest().getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    /** Starts the version's trial server in the background, so the first {@code try} doesn't wait for it: {@code 202}. */
    @PostMapping("/{id}/versions/{version}/try/warm")
    public ResponseEntity<byte[]> warmTry(HttpServletRequest request, @PathVariable String id, @PathVariable String version) {
        who(request).require("builds:run");
        Model.VersionRef ref = servers.version(servers.get(id), version);
        trials.warm(servers.files(ref), ref.created());
        return Json.status(202, Map.of("warming", true));
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
                    answer.put("operations", compilation.operations(result.version(), Instant.now().toString(), false));
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
        if (server.system()) {
            s.put("system", true);
        }
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
