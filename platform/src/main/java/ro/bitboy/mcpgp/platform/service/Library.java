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
