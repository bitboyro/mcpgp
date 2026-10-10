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
    private final PlatformConfig config;

    public LibraryApi(Store store, Library library, PlatformConfig config) {
        this.store = store;
        this.library = library;
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
        return Json.ok(item(library.get(kind, name)));
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
