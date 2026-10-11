package ro.bitboy.mcpgp.platform.store;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The platform's data (spec/platform-api.md §7.1). One interface, two implementations: the file store
 * ({@link FileStore}) and PostgreSQL ({@link PostgresStore}); {@code storage.type} picks one. Blobs and
 * versions are immutable and content-addressed.
 */
public interface Store {

    /** The store format this release reads and writes. Before 1.0.0 other formats are refused, not migrated. */
    String FORMAT = "0.1";

    /** Throws when the store can't be reached (the health check). */
    default void check() {
    }

    /** Which store this is, for the log and the health check: {@code files (/var/lib/mcpgp)}. */
    String describe();

    // servers
    List<Model.Server> servers();

    Optional<Model.Server> server(String id);

    /**
     * Creates or replaces a server record.
     *
     * @param expectedRevision the revision the caller read, or -1 for a new server
     * @throws ConflictException when the stored revision differs (or the server exists, for -1)
     */
    Model.Server saveServer(Model.Server server, long expectedRevision);

    // library
    List<Model.LibraryItem> library();

    Optional<Model.LibraryItem> libraryItem(String kind, String name);

    /** Like {@link #saveServer}: {@code expectedRevision} -1 for a new item. */
    Model.LibraryItem saveLibraryItem(Model.LibraryItem item, long expectedRevision);

    // content
    String putBlob(byte[] content);

    byte[] blob(String digest);

    void saveVersion(Model.Version version);

    Optional<Model.Version> version(String digest);

    // builds
    List<Model.Build> builds();

    Optional<Model.Build> build(String id);

    void saveBuild(Model.Build build);

    void writeBuildFile(String build, String name, byte[] content);

    void appendBuildLog(String build, String text);

    Optional<byte[]> buildFile(String build, String name);

    /** Removes a build's files (artifacts and log); the record stays. */
    void deleteBuildFiles(String build);

    // identities and tokens
    List<Model.Identity> identities();

    void saveIdentity(Model.Identity identity);

    List<Model.Token> tokens();

    void saveToken(Model.Token token);

    // local accounts and approvals
    List<Model.Credential> credentials();

    void saveCredential(Model.Credential credential);

    List<Model.Approval> approvals();

    void saveApproval(Model.Approval approval);

    // activity
    void append(Model.Activity activity);

    List<Model.Activity> activity(Predicate<Model.Activity> filter, int limit);

    // derived data, rebuildable at any time (e.g. compiled manifests by version digest)
    Optional<byte[]> cached(String kind, String key);

    void cache(String kind, String key, byte[] content);

    /** A stale or duplicate write. */
    final class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }
}
