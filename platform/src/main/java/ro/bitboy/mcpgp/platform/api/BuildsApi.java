package ro.bitboy.mcpgp.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mcpgp.platform.service.Access;
import ro.bitboy.mcpgp.platform.service.Builds;
import ro.bitboy.mcpgp.platform.service.Servers;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Builds (spec/platform-api.md §2.2). */
@RestController
@RequestMapping("/api/v1")
public class BuildsApi {

    private final Store store;
    private final Servers servers;
    private final Builds builds;

    public BuildsApi(Store store, Servers servers, Builds builds) {
        this.store = store;
        this.servers = servers;
        this.builds = builds;
    }

    /** {@code {version?: number|"latest", buildNumber?, versionOverride?}}. */
    @PostMapping("/servers/{id}/builds")
    public ResponseEntity<byte[]> start(HttpServletRequest request, @PathVariable String id, @RequestBody(required = false) byte[] body) {
        Access.Principal who = ServersApi.who(request);
        who.require("builds:run");
        JsonNode json = Json.parse(body);
        Model.Server server = servers.get(id);
        Model.VersionRef version = servers.version(server, json.path("version").asText("latest"));
        Integer buildNumber = json.hasNonNull("buildNumber") ? json.get("buildNumber").asInt() : null;
        if (buildNumber != null && buildNumber < 1) {
            throw ApiException.validation("buildNumber must be at least 1");
        }
        Model.Build build = builds.queue(server, version, buildNumber, json.path("versionOverride").asText(null), who);
        return Json.status(202, build(build));
    }

    @GetMapping("/servers/{id}/builds")
    public ResponseEntity<byte[]> list(HttpServletRequest request, @PathVariable String id, @RequestParam(defaultValue = "false") boolean archived) {
        ServersApi.who(request).require("servers:read");
        servers.get(id);
        List<Map<String, Object>> items = new ArrayList<>();
        store.builds().stream().filter(b -> b.server().equals(id) && b.archived() == archived)
                .sorted(Comparator.comparing(Model.Build::created).reversed()).forEach(b -> items.add(build(b)));
        return Json.ok(Map.of("items", items));
    }

    @GetMapping("/builds/{build}")
    public ResponseEntity<byte[]> get(HttpServletRequest request, @PathVariable String build) {
        ServersApi.who(request).require("servers:read");
        return Json.ok(build(find(build)));
    }

    @GetMapping("/builds/{build}/log")
    public ResponseEntity<byte[]> log(HttpServletRequest request, @PathVariable String build) {
        ServersApi.who(request).require("servers:read");
        find(build);
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(builds.logText(build).getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/builds/{build}/artifacts")
    public ResponseEntity<byte[]> artifacts(HttpServletRequest request, @PathVariable String build) {
        ServersApi.who(request).require("servers:read");
        Model.Build b = find(build);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", b.artifacts());
        b.artifacts().stream().filter(a -> a.name().endsWith(".zip")).findFirst().ifPresent(zip -> body.put("packageFiles", packageFiles(b, zip)));
        return Json.ok(body);
    }

    /** An artifact (the zip or the manifest), or with {@code ?file=} one file of the package. */
    @GetMapping("/builds/{build}/artifacts/{name}")
    public ResponseEntity<byte[]> download(HttpServletRequest request, @PathVariable String build, @PathVariable String name,
                                           @RequestParam(required = false) String file) {
        Access.Principal who = ServersApi.who(request);
        who.require("servers:read");
        Model.Build b = find(build);
        if (b.deleted() || b.artifacts().stream().noneMatch(a -> a.name().equals(name))) {
            throw ApiException.notFound("artifact " + name + (b.deleted() ? " (the build's files were deleted)" : ""));
        }
        byte[] content = store.buildFile(build, name).orElseThrow(() -> ApiException.notFound("artifact " + name));
        String downloaded = name;
        String filename = name;
        if (file != null) {
            content = entry(content, file);
            downloaded = name + "!" + file;
            filename = file.substring(file.lastIndexOf('/') + 1);
        }
        store.append(new Model.Activity(Instant.now().toString(), who.identity().name(), who.identity().kind(), "artifact.download", build,
                "ok", Map.of("artifact", downloaded)));
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"" + filename.replace("\"", "") + "\"")
                .contentType(filename.endsWith(".json") ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_OCTET_STREAM).body(content);
    }

    @PostMapping("/builds/{build}/release")
    public ResponseEntity<byte[]> release(HttpServletRequest request, @PathVariable String build) {
        Access.Principal who = ServersApi.who(request);
        who.require("builds:release");
        if (who.identity().kind().equals("agent")) {
            throw new ApiException(403, "APPROVAL_REQUIRED", "agents can't release builds; a person releases it (spec/platform-api.md §5)");
        }
        return Json.ok(build(builds.release(find(build), who.identity().name(), who.identity().kind())));
    }

    @PostMapping("/builds/{build}/archive")
    public ResponseEntity<byte[]> archive(HttpServletRequest request, @PathVariable String build) {
        Access.Principal who = ServersApi.who(request);
        who.require("builds:delete");
        return Json.ok(build(builds.archive(find(build), true, who.identity().name(), who.identity().kind())));
    }

    @PostMapping("/builds/{build}/restore")
    public ResponseEntity<byte[]> restore(HttpServletRequest request, @PathVariable String build) {
        Access.Principal who = ServersApi.who(request);
        who.require("builds:delete");
        return Json.ok(build(builds.archive(find(build), false, who.identity().name(), who.identity().kind())));
    }

    @DeleteMapping("/builds/{build}")
    public ResponseEntity<byte[]> delete(HttpServletRequest request, @PathVariable String build) {
        Access.Principal who = ServersApi.who(request);
        who.require("builds:delete");
        if (who.identity().kind().equals("agent")) {
            throw new ApiException(403, "FORBIDDEN", "agents can't delete builds (spec/platform-api.md §5)");
        }
        return Json.ok(build(builds.delete(find(build), who.identity().name(), who.identity().kind())));
    }

    private Model.Build find(String id) {
        try {
            return store.build(id).orElseThrow(() -> ApiException.notFound("build " + id));
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("build " + id);
        }
    }

    private List<Map<String, Object>> packageFiles(Model.Build b, Model.Artifact zip) {
        List<Map<String, Object>> files = new ArrayList<>();
        store.buildFile(b.id(), zip.name()).ifPresent(content -> {
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(content))) {
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    files.add(Map.of("path", entry.getName(), "size", in.readAllBytes().length));
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        return files;
    }

    private static byte[] entry(byte[] zip, String path) {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals(path)) {
                    return in.readAllBytes();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        throw ApiException.notFound("file " + path + " in the package");
    }

    static Map<String, Object> build(Model.Build b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id());
        m.put("server", b.server());
        m.put("version", b.version());
        m.put("status", b.status());
        m.put("startedBy", b.startedBy());
        m.put("created", b.created());
        m.put("started", b.started());
        m.put("finished", b.finished());
        m.put("serverVersion", b.serverVersion());
        m.put("released", b.released());
        if (b.releasedBy() != null) {
            m.put("releasedBy", b.releasedBy());
        }
        m.put("archived", b.archived());
        m.put("deleted", b.deleted());
        m.put("artifacts", b.artifacts());
        if (b.error() != null) {
            m.put("error", b.error());
        }
        return m;
    }
}
