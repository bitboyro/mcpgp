package ro.bitboy.mcpgp.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import ro.bitboy.mcpgp.platform.api.ApiException;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Changes to a server's definition made on its pages (spec/platform-api.md §2.1, edits): applied to
 * the root definition of a base version, previewed, and saved as a new version. The base must be the
 * latest version, so two people editing at once don't overwrite each other.
 */
public final class Edits {

    /** The edited files as a stored version (not yet a version of the server), and its compilation. */
    public record Result(Model.Version version, String definition, Compilation.Preview preview) {
        public boolean compiles() {
            return preview.manifest() != null;
        }
    }

    private final Store store;
    private final Servers servers;
    private final Compilation compilation;
    private final Projects projects;

    public Edits(Store store, Servers servers, Compilation compilation) {
        this.store = store;
        this.servers = servers;
        this.compilation = compilation;
        this.projects = new Projects(store);
    }

    public Result preview(String id, int base, List<YamlEdit.Change> changes) {
        return preview(id, base, changes, Map.of());
    }

    /** An edit that also adds or replaces files of the project (e.g. a new contract's spec). */
    public Result preview(String id, int base, List<YamlEdit.Change> changes, Map<String, byte[]> added) {
        if (changes == null || changes.isEmpty()) {
            throw ApiException.validation("changes: at least one");
        }
        Model.Server server = servers.get(id);
        if (server.latest() == null) {
            throw new ApiException(409, "CONFLICT", "server " + id + " has no version to edit yet; upload one");
        }
        if (server.latest().number() != base) {
            throw new ApiException(409, "CONFLICT", "version " + base + " is not the latest any more (" + server.latest().number()
                    + " is); reload and make the changes again", Map.of("latest", server.latest().number()));
        }
        Model.Version files = servers.files(server.latest());
        String root = new String(store.blob(files.files().get(files.root())), StandardCharsets.UTF_8);
        String edited = YamlEdit.apply(root, changes);
        Map<String, byte[]> contents = new TreeMap<>();
        files.files().forEach((path, blob) -> contents.put(path, store.blob(blob)));
        contents.putAll(added);
        contents.put(files.root(), edited.getBytes(StandardCharsets.UTF_8));
        // stored by content, like an upload; only a save makes it a version of the server
        Model.Version version = projects.store(contents);
        return new Result(version, edited, compilation.preview(version, Instant.now().toString()));
    }

    /** Saves the edit as the server's next version; refused when it doesn't compile. */
    public Model.Server save(String id, int base, List<YamlEdit.Change> changes, String message, Access.Principal who) {
        return save(id, base, changes, Map.of(), message, who);
    }

    public Model.Server save(String id, int base, List<YamlEdit.Change> changes, Map<String, byte[]> added, String message, Access.Principal who) {
        Result result = preview(id, base, changes, added);
        if (!result.compiles()) {
            throw new ApiException(422, "VALIDATION", "with these changes the definition doesn't compile",
                    Map.of("diagnostics", ro.bitboy.mcpgp.platform.api.ServersApi.diagnostics(result.preview().result().diagnostics())));
        }
        return servers.addVersion(id, result.version(), message, who);
    }

    public static List<YamlEdit.Change> changes(JsonNode json) {
        JsonNode list = json.path("changes");
        if (!list.isArray()) {
            throw ApiException.validation("changes: a list of { op, path, value }");
        }
        List<YamlEdit.Change> changes = new java.util.ArrayList<>();
        for (JsonNode c : list) {
            changes.add(new YamlEdit.Change(c.path("op").asText("set"), c.path("path").asText(null), c.has("value") ? c.get("value") : null));
        }
        if (changes.size() > 50) {
            throw ApiException.validation("changes: at most 50 at once");
        }
        return changes;
    }
}
