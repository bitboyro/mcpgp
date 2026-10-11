package ro.bitboy.mcpgp.platform.service;

import ro.bitboy.mcpgp.platform.api.ApiException;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The library (spec/platform-api.md §2.3): shared, versioned file sets that definitions reference as
 * {@code mcpgp://<workspace>/<kind>/<name>@<version>}. Servers are referenced from the servers themselves.
 */
public final class Library {

    public static final Set<String> KINDS = Set.of("contracts", "naming", "rules", "packs", "templates");

    private final Store store;
    private final Projects projects;
    private final ro.bitboy.mcpgp.schemas.Schemas schemas = new ro.bitboy.mcpgp.schemas.Schemas();

    public Library(Store store) {
        this.store = store;
        this.projects = new Projects(store);
    }

    public Model.LibraryItem create(String kind, String name, String title, String root, byte[] body, String contentType, String message,
                                    Access.Principal who) {
        check(kind, name);
        if (store.libraryItem(kind, name).isPresent()) {
            throw new ApiException(409, "CONFLICT", kind + "/" + name + " exists; add a version instead");
        }
        Model.Version version = projects.storeItem(body, contentType, root);
        String now = Instant.now().toString();
        Model.LibraryItem item = store.saveLibraryItem(new Model.LibraryItem(kind, name, title == null ? name : title, now, now,
                List.of(new Model.VersionRef(1, version.digest(), message, who.identity().name(), now)), 0), -1);
        record(who, "library.create", kind + "/" + name, Map.of("version", 1));
        return item;
    }

    public Model.LibraryItem addVersion(String kind, String name, String root, byte[] body, String contentType, String message, Access.Principal who) {
        Model.LibraryItem item = get(kind, name);
        Model.Version version = projects.storeItem(body, contentType, root);
        if (item.latest() != null && item.latest().digest().equals(version.digest())) {
            return item;
        }
        String now = Instant.now().toString();
        List<Model.VersionRef> versions = new ArrayList<>(item.versions());
        int number = item.latest().number() + 1;
        versions.add(new Model.VersionRef(number, version.digest(), message, who.identity().name(), now));
        Model.LibraryItem saved;
        try {
            saved = store.saveLibraryItem(new Model.LibraryItem(kind, name, item.title(), item.created(), now, versions, item.revision()),
                    item.revision());
        } catch (Store.ConflictException e) {
            throw new ApiException(412, "PRECONDITION_FAILED", e.getMessage() + "; retry");
        }
        record(who, "library.version", kind + "/" + name, Map.of("version", number));
        return saved;
    }

    /** An edit of a naming policy: the edited file, the schema's problems with it (empty when it's valid). */
    public record Edit(String text, List<String> problems) {
    }

    /**
     * Applies changes to the latest version of a naming policy (spec/platform-api.md §2.3), keeping the
     * file's comments and layout like server edits; {@code base} must be the latest version number.
     */
    public Edit preview(String kind, String name, int base, List<YamlEdit.Change> changes) {
        if (!kind.equals("naming")) {
            throw ApiException.validation("only naming policies can be edited on the platform; add a version of " + kind + " items instead");
        }
        if (changes == null || changes.isEmpty()) {
            throw ApiException.validation("changes: at least one");
        }
        Model.LibraryItem item = get(kind, name);
        if (item.latest().number() != base) {
            throw new ApiException(409, "CONFLICT", "version " + base + " is not the latest any more (" + item.latest().number()
                    + " is); reload and make the changes again", Map.of("latest", item.latest().number()));
        }
        Model.Version files = store.version(item.latest().digest()).orElseThrow();
        String text = YamlEdit.apply(new String(store.blob(files.files().get(files.root())), java.nio.charset.StandardCharsets.UTF_8), changes);
        List<String> problems = new ArrayList<>();
        try {
            com.fasterxml.jackson.databind.JsonNode policy = new com.fasterxml.jackson.databind.ObjectMapper(
                    new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readTree(text);
            schemas.problems(ro.bitboy.mcpgp.schemas.SchemaKind.NAMING_POLICY, policy)
                    .forEach(p -> problems.add((p.pointer() == null || p.pointer().isEmpty() ? "" : p.pointer() + ": ") + p.message()));
        } catch (java.io.IOException e) {
            problems.add("not valid YAML: " + e.getMessage());
        }
        return new Edit(text, List.copyOf(problems));
    }

    /** Saves an edit of a naming policy as its next version; refused when the result isn't a valid policy. */
    public Model.LibraryItem saveEdit(String kind, String name, int base, List<YamlEdit.Change> changes, String message, Access.Principal who) {
        Edit edit = preview(kind, name, base, changes);
        if (!edit.problems().isEmpty()) {
            throw new ApiException(422, "VALIDATION", "with these changes the policy isn't valid: " + String.join("; ", edit.problems()));
        }
        Model.Version files = store.version(get(kind, name).latest().digest()).orElseThrow();
        byte[] body;
        try {
            body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(Map.of("files", Map.of(files.root(), edit.text())));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return addVersion(kind, name, files.root(), body, "application/json", message, who);
    }

    /** The servers whose latest version extends this naming policy, and the version each one pins (null: the latest). */
    public List<Map<String, Object>> usedBy(String kind, String name, String workspace) {
        java.util.regex.Pattern reference = java.util.regex.Pattern.compile("^mcpgp://" + java.util.regex.Pattern.quote(workspace) + "/"
                + java.util.regex.Pattern.quote(kind) + "/" + java.util.regex.Pattern.quote(name) + "(?:@(\\w+))?$");
        List<Map<String, Object>> users = new ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper yaml = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        for (Model.Server server : store.servers()) {
            if (server.latest() == null || server.archived()) {
                continue;
            }
            Model.Version files = store.version(server.latest().digest()).orElse(null);
            if (files == null || files.files().get(files.root()) == null) {
                continue;
            }
            try {
                String ref = yaml.readTree(store.blob(files.files().get(files.root()))).at("/naming/extends").asText("");
                java.util.regex.Matcher m = reference.matcher(ref);
                if (m.matches()) {
                    Map<String, Object> user = new java.util.LinkedHashMap<>();
                    user.put("server", server.id());
                    user.put("pinned", m.group(1) == null || m.group(1).equals("latest") ? null : m.group(1));
                    users.add(user);
                }
            } catch (java.io.IOException e) {
                // not readable: it doesn't compile either
            }
        }
        return users;
    }

    public Model.LibraryItem get(String kind, String name) {
        try {
            check(kind, name);
            return store.libraryItem(kind, name).orElseThrow(() -> ApiException.notFound(kind + "/" + name));
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound(kind + "/" + name);
        }
    }

    private static void check(String kind, String name) {
        if (!KINDS.contains(kind)) {
            throw ApiException.validation("kind: " + String.join(", ", KINDS.stream().sorted().toList())
                    + " (servers are referenced from /servers)");
        }
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) {
            throw ApiException.validation("name: letters, digits, '.', '_' and '-', at most 80");
        }
    }

    private void record(Access.Principal who, String action, String target, Map<String, Object> details) {
        store.append(new Model.Activity(Instant.now().toString(), who.identity().name(), who.identity().kind(), action, target, "ok", details));
    }
}
