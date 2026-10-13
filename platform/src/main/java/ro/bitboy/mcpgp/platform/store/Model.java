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

    /**
     * A shared item of the library (spec/platform-api.md §2.3), referenced as
     * {@code mcpgp://<workspace>/<kind>/<name>@<version>}.
     */
    public record LibraryItem(String kind, String name, String title, String created, String updated, List<VersionRef> versions,
                              long revision) {
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

    /**
     * Compiling and packaging one version (spec/platform-api.md §2.2).
     *
     * @param status        queued, running, succeeded, failed
     * @param serverVersion the version the package carries (e.g. 2.0.0+3), once known
     * @param artifacts     stored files: the package zip and the manifest, with sizes and SHA-256
     * @param deleted       files removed; the record stays
     */
    public record Build(String id, String server, int version, String versionDigest, String status, String startedBy,
                        String startedByKind, String created, String started, String finished, Integer buildNumber,
                        String serverVersion, boolean released, String releasedBy, boolean archived, String archivedAt,
                        boolean deleted, List<Artifact> artifacts, String error) {
    }

    public record Artifact(String name, long size, String sha256) {
    }

    /** A person, agent or pipeline. */
    /**
     * {@code external}: the directory account a person signs in with ({@code oidc:<issuer>|<sub>} or
     * {@code ldap:<dn>}), or null. Identities the platform created from a directory have the owner
     * {@code directory}; their scopes follow the person's groups at every sign-in.
     */
    public record Identity(String id, String kind, String name, Set<String> scopes, String owner, String created, boolean disabled,
                           String external) {
        public Identity(String id, String kind, String name, Set<String> scopes, String owner, String created, boolean disabled) {
            this(id, kind, name, scopes, owner, created, disabled, null);
        }
    }

    /** An API token: only the hash of its secret is stored. */
    public record Token(String id, String identity, String hash, Set<String> scopes, String label, String created, String expires,
                        boolean revoked) {
    }

    /**
     * A local account's password (spec/platform-api.md §3): PBKDF2-HMAC-SHA256, salted.
     *
     * @param failures    sign-in failures in a row
     * @param lockedUntil no sign-in before this time, or null
     */
    /** @param mustChange set by an admin's temporary password: the person chooses a new one before anything else */
    public record Credential(String identity, String salt, int iterations, String hash, int failures, String lockedUntil, boolean mustChange) {
        public Credential(String identity, String salt, int iterations, String hash, int failures, String lockedUntil) {
            this(identity, salt, iterations, hash, failures, lockedUntil, false);
        }
    }

    /** A request waiting for a person's decision (spec/platform-api.md §5), e.g. an agent's release. */
    public record Approval(String id, String action, String target, String requestedBy, String requestedAt, String status,
                           String decidedBy, String decidedAt, String comment) {
    }

    /** One entry of the activity log. Never contains secrets or token values. */
    public record Activity(String ts, String actor, String actorKind, String action, String target, String outcome,
                           Map<String, Object> details) {
    }
}
