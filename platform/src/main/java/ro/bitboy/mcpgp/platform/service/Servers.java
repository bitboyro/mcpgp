package ro.bitboy.mcpgp.platform.service;

import ro.bitboy.mcpgp.platform.api.ApiException;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Servers and their versions (spec/platform-api.md §2.1); every change goes to the activity log. */
public final class Servers {

    private final Store store;
    private final Projects projects;

    public Servers(Store store) {
        this.store = store;
        this.projects = new Projects(store);
    }

    public Model.Server create(String id, String title, byte[] body, String contentType, String message, Access.Principal who) {
        return create(id, title, () -> projects.store(body, contentType), message, who);
    }

    /** A new server from files made on the platform (e.g. from an uploaded spec). */
    public Model.Server create(String id, String title, Map<String, byte[]> files, String message, Access.Principal who) {
        return create(id, title, () -> projects.store(files), message, who);
    }

    private Model.Server create(String id, String title, java.util.function.Supplier<Model.Version> files, String message, Access.Principal who) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{1,62}")) {
            throw ApiException.validation("id: lowercase letters, digits and '-', 2–63 characters");
        }
        if (store.server(id).isPresent()) {
            throw new ApiException(409, "CONFLICT", "a server with id " + id + " exists");
        }
        Model.Version version = files.get();
        String now = Instant.now().toString();
        Model.Server server = new Model.Server(id, title == null || title.isBlank() ? id : title, null, false, now, now,
                List.of(new Model.VersionRef(1, version.digest(), message, who.identity().name(), now)), 0);
        try {
            server = store.saveServer(server, -1);
        } catch (Store.ConflictException e) {
            throw new ApiException(409, "CONFLICT", "a server with id " + id + " exists");
        }
        record(who, "server.create", id, Map.of("version", 1, "digest", version.digest()));
        return server;
    }

    /** Adds a version; content equal to the latest version adds nothing and returns the server unchanged. */
    public Model.Server addVersion(String id, byte[] body, String contentType, String message, Access.Principal who) {
        if (get(id).archived()) {
            throw new ApiException(409, "CONFLICT", "server " + id + " is archived; restore it first");
        }
        return addVersion(id, projects.store(body, contentType), message, who);
    }

    /** Adds a stored version (an upload, or an edit of the latest one); the same content as the latest one adds nothing. */
    public Model.Server addVersion(String id, Model.Version version, String message, Access.Principal who) {
        Model.Server server = get(id);
        if (server.archived()) {
            throw new ApiException(409, "CONFLICT", "server " + id + " is archived; restore it first");
        }
        if (server.latest() != null && server.latest().digest().equals(version.digest())) {
            return server;
        }
        String now = Instant.now().toString();
        List<Model.VersionRef> versions = new ArrayList<>(server.versions());
        int number = versions.isEmpty() ? 1 : server.latest().number() + 1;
        versions.add(new Model.VersionRef(number, version.digest(), message, who.identity().name(), now));
        Model.Server saved = save(new Model.Server(server.id(), server.title(), server.parent(), false, server.created(), now, versions,
                server.revision()), server.revision());
        record(who, "version.create", id, Map.of("version", number, "digest", version.digest()));
        return saved;
    }

    /** An independent copy of the latest version's files (spec/platform-api.md §2.1). */
    public Model.Server duplicate(String sourceId, String id, String title, Access.Principal who) {
        Model.Server source = get(sourceId);
        Model.Version files = files(source.latest());
        return createFrom(id, title, null, files, "duplicate of " + sourceId + " version " + source.latest().number(), who, "server.duplicate",
                Map.of("source", sourceId));
    }

    /**
     * A variant: a new server whose definition extends this one ({@code @latest}, or the current version
     * with {@code pin}) and holds only its own overrides (ADR-0010).
     */
    public Model.Server variant(String parentId, String id, String title, boolean pin, String workspace, Access.Principal who) {
        Model.Server parent = get(parentId);
        String reference = "mcpgp://" + workspace + "/servers/" + parentId + "@" + (pin ? String.valueOf(parent.latest().number()) : "latest");
        String definition = "# A variant of " + parentId + ": everything not set here comes from it.\n"
                + "apiVersion: mcpgp/v1\nkind: McpServer\nmetadata:\n  name: " + id + "\n"
                + (title == null ? "" : "  title: \"" + title.replace("\"", "'") + "\"\n")
                + "extends: " + reference + "\n";
        Model.Version files = projects.store(Map.of(id + ".mcpgp.yaml", definition.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return createFrom(id, title, parentId, files, "variant of " + parentId, who, "server.variant", Map.of("parent", parentId,
                "extends", reference));
    }

    private Model.Server createFrom(String id, String title, String parent, Model.Version files, String message, Access.Principal who,
                                    String action, Map<String, Object> details) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{1,62}")) {
            throw ApiException.validation("id: lowercase letters, digits and '-', 2–63 characters");
        }
        String now = Instant.now().toString();
        Model.Server server;
        try {
            server = store.saveServer(new Model.Server(id, title == null || title.isBlank() ? id : title, parent, false, now, now,
                    List.of(new Model.VersionRef(1, files.digest(), message, who.identity().name(), now)), 0), -1);
        } catch (Store.ConflictException e) {
            throw new ApiException(409, "CONFLICT", "a server with id " + id + " exists");
        }
        record(who, action, id, details);
        return server;
    }

    public Model.Server setArchived(String id, boolean archived, long expectedRevision, Access.Principal who) {
        Model.Server server = get(id);
        Model.Server saved = save(new Model.Server(server.id(), server.title(), server.parent(), archived, server.created(),
                Instant.now().toString(), server.versions(), server.revision()), expectedRevision < 0 ? server.revision() : expectedRevision);
        record(who, archived ? "server.archive" : "server.restore", id, Map.of());
        return saved;
    }

    public Model.Server get(String id) {
        try {
            return store.server(id).orElseThrow(() -> ApiException.notFound("server " + id));
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("server " + id);
        }
    }

    /** A version by number or {@code latest}. */
    public Model.VersionRef version(Model.Server server, String number) {
        if (number.equals("latest")) {
            if (server.latest() == null) {
                throw ApiException.notFound("version");
            }
            return server.latest();
        }
        try {
            int n = Integer.parseInt(number);
            return server.versions().stream().filter(v -> v.number() == n).findFirst()
                    .orElseThrow(() -> ApiException.notFound("version " + number + " of " + server.id()));
        } catch (NumberFormatException e) {
            throw ApiException.notFound("version " + number);
        }
    }

    public Model.Version files(Model.VersionRef ref) {
        return store.version(ref.digest()).orElseThrow(() -> ApiException.notFound("version content"));
    }

    private Model.Server save(Model.Server server, long expected) {
        try {
            return store.saveServer(server, expected);
        } catch (Store.ConflictException e) {
            throw new ApiException(412, "PRECONDITION_FAILED", e.getMessage() + "; read it again and retry");
        }
    }

    void record(Access.Principal who, String action, String target, Map<String, Object> details) {
        store.append(new Model.Activity(Instant.now().toString(), who.identity().name(), who.identity().kind(), action, target, "ok", details));
    }
}
