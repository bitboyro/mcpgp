package ro.bitboy.mcpgp.platform.store;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** What the platform stores (spec/platform-api.md §2). Times are ISO-8601 UTC strings; records are stored as JSON. */
public final class Model {

    private Model() {
    }

    /**
     * A server definition project.
     *
     * @param parent   the server this one is a variant of, or null
     * @param revision increases with every change of this record (ETag, optimistic concurrency)
     */
    public record Server(String id, String title, String parent, boolean archived, String created, String updated,
                         List<VersionRef> versions, long revision) {
        public VersionRef latest() {
            return versions.isEmpty() ? null : versions.get(versions.size() - 1);
        }
    }

    /** A version of a server: number, content digest, who made it and why. */
    public record VersionRef(int number, String digest, String message, String author, String created) {
    }

    /** The files of a version (immutable, content-addressed): path → blob digest; {@code root} is the root definition. */
    public record Version(String digest, String root, Map<String, String> files) {
    }

    /** A person, agent or pipeline. */
    public record Identity(String id, String kind, String name, Set<String> scopes, String owner, String created, boolean disabled) {
    }

    /** An API token: only the hash of its secret is stored. */
    public record Token(String id, String identity, String hash, Set<String> scopes, String label, String created, String expires,
                        boolean revoked) {
    }

    /** One entry of the activity log. Never contains secrets or token values. */
    public record Activity(String ts, String actor, String actorKind, String action, String target, String outcome,
                           Map<String, Object> details) {
    }
}
