package ro.bitboy.mcpgp.platform.api;

import jakarta.servlet.http.HttpServletRequest;
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
import ro.bitboy.mcpgp.platform.PlatformConfig;
import ro.bitboy.mcpgp.platform.service.Access;
import ro.bitboy.mcpgp.platform.service.Library;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The library (spec/platform-api.md §2.3). */
@RestController
@RequestMapping("/api/v1/library")
public class LibraryApi {

    private final Store store;
    private final Library library;
    private final ro.bitboy.mcpgp.platform.service.SharedNotices sharedNotices;
    private final PlatformConfig config;

    public LibraryApi(Store store, Library library, ro.bitboy.mcpgp.platform.service.SharedNotices sharedNotices, PlatformConfig config) {
        this.store = store;
        this.library = library;
        this.sharedNotices = sharedNotices;
        this.config = config;
    }

    @GetMapping
    public ResponseEntity<byte[]> list(HttpServletRequest request) {
        ServersApi.who(request).require("servers:read");
        List<Map<String, Object>> items = new ArrayList<>();
        store.library().forEach(i -> items.add(item(i)));
        return Json.ok(Map.of("workspace", config.workspace(), "items", items));
    }

    @PostMapping("/{kind}")
    public ResponseEntity<byte[]> create(HttpServletRequest request, @PathVariable String kind, @RequestParam String name,
                                         @RequestParam(required = false) String title, @RequestParam(required = false) String root,
                                         @RequestParam(required = false) String message,
                                         @RequestHeader(value = "Content-Type", required = false) String contentType,
                                         @RequestBody(required = false) byte[] body) {
        Access.Principal who = ServersApi.who(request);
        who.require("library:write");
        return Json.status(201, item(library.create(kind, name, title, root, body == null ? new byte[0] : body, contentType, message, who)));
    }

    @GetMapping("/{kind}/{name}")
    public ResponseEntity<byte[]> get(HttpServletRequest request, @PathVariable String kind, @PathVariable String name) {
        ServersApi.who(request).require("servers:read");
        Model.LibraryItem item = library.get(kind, name);
        Map<String, Object> answer = item(item);
        if (kind.equals("naming") || kind.equals("notices")) {
            // the policy or the notices as data (the editors on the Library page work from it) and who uses it
            Model.Version files = store.version(item.latest().digest()).orElseThrow();
            answer.put("root", files.root());
            String field = kind.equals("naming") ? "policy" : "set";
            try {
                answer.put(field, new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                        .readTree(store.blob(files.files().get(files.root()))));
            } catch (java.io.IOException e) {
                answer.put(field, null);
            }
            answer.put("usedBy", library.usedBy(kind, name, config.workspace()));
        }
        return Json.ok(answer);
    }

    @PostMapping("/{kind}/{name}/versions")
    public ResponseEntity<byte[]> addVersion(HttpServletRequest request, @PathVariable String kind, @PathVariable String name,
                                             @RequestParam(required = false) String root, @RequestParam(required = false) String message,
                                             @RequestHeader(value = "Content-Type", required = false) String contentType,
                                             @RequestBody(required = false) byte[] body) {
        Access.Principal who = ServersApi.who(request);
        who.require("library:write");
        long before = library.get(kind, name).revision();
        Model.LibraryItem item = library.addVersion(kind, name, root, body == null ? new byte[0] : body, contentType, message, who);
        return Json.status(item.revision() == before ? 200 : 201, item(item));
    }

    /**
     * Edits a naming policy: {@code {base, changes: [{op, path, value}], message?, dryRun?}}. A dry run
     * answers the edited file, its problems and the servers that use the policy (with the version they
     * pin); otherwise the edit becomes the policy's next version.
     */
    @PostMapping("/{kind}/{name}/edits")
    public ResponseEntity<byte[]> edit(HttpServletRequest request, @PathVariable String kind, @PathVariable String name,
                                       @RequestBody(required = false) byte[] body) {
        Access.Principal who = ServersApi.who(request);
        who.require("library:write");
        com.fasterxml.jackson.databind.JsonNode json = Json.parse(body);
        if (!json.path("base").canConvertToInt()) {
            throw ApiException.validation("base: the version number the changes were made on");
        }
        int base = json.path("base").asInt();
        List<ro.bitboy.mcpgp.platform.service.YamlEdit.Change> changes = ro.bitboy.mcpgp.platform.service.Edits.changes(json);
        if (json.path("dryRun").asBoolean(false)) {
            Library.Edit edit = library.preview(kind, name, base, changes);
            return Json.ok(Map.of("base", base, "text", edit.text(), "problems", edit.problems(), "usedBy", library.usedBy(kind, name, config.workspace())));
        }
        return Json.status(201, item(library.saveEdit(kind, name, base, changes, json.path("message").asText(null), who)));
    }

    /**
     * Shared notices on servers: {@code {servers?: [id], version?: n, message?}}. Adds the item to each
     * server's {@code descriptions.include} (every server that isn't archived when {@code servers} is
     * left out), or moves the servers that include it to {@code version} (default: the latest). Each
     * changed server gets its next version; the answer says what happened to each one.
     */
    @PostMapping("/{kind}/{name}/apply")
    public ResponseEntity<byte[]> apply(HttpServletRequest request, @PathVariable String kind, @PathVariable String name,
                                        @RequestBody(required = false) byte[] body) {
        Access.Principal who = ServersApi.who(request);
        who.require("servers:write");
        if (!kind.equals("notices")) {
            throw ApiException.validation("only shared notices are applied to servers; servers reference other kinds in their definition");
        }
        com.fasterxml.jackson.databind.JsonNode json = Json.parse(body);
        List<String> servers = null;
        if (json.has("servers")) {
            if (!json.path("servers").isArray()) {
                throw ApiException.validation("servers: a list of server ids");
            }
            servers = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode id : json.path("servers")) {
                servers.add(id.asText());
            }
        }
        Integer version = json.hasNonNull("version") ? Integer.valueOf(json.path("version").asInt()) : null;
        return Json.ok(Map.of("results", sharedNotices.apply(name, servers, version, json.path("message").asText(null), who)));
    }

    @GetMapping("/{kind}/{name}/versions/{version}/files/{*path}")
    public ResponseEntity<byte[]> file(HttpServletRequest request, @PathVariable String kind, @PathVariable String name,
                                       @PathVariable String version, @PathVariable String path) {
        ServersApi.who(request).require("servers:read");
        Model.LibraryItem item = library.get(kind, name);
        Model.VersionRef ref = version.equals("latest") ? item.latest() : item.versions().stream()
                .filter(v -> String.valueOf(v.number()).equals(version)).findFirst().orElseThrow(() -> ApiException.notFound("version " + version));
        Model.Version files = store.version(ref.digest()).orElseThrow();
        String blob = files.files().get(path.startsWith("/") ? path.substring(1) : path);
        if (blob == null) {
            throw ApiException.notFound("file " + path);
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(store.blob(blob));
    }

    private Map<String, Object> item(Model.LibraryItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", item.kind());
        m.put("name", item.name());
        m.put("title", item.title());
        m.put("reference", "mcpgp://" + config.workspace() + "/" + item.kind() + "/" + item.name() + "@" + item.latest().number());
        List<Map<String, Object>> versions = new ArrayList<>();
        item.versions().forEach(v -> versions.add(ServersApi.versionRef(v)));
        m.put("versions", versions);
        m.put("updated", item.updated());
        return m;
    }
}
