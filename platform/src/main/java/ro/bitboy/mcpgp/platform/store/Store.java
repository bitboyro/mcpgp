package ro.bitboy.mcpgp.platform.store;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The platform's data (spec/platform-api.md §7.1). One interface, two planned implementations: the
 * file store now, PostgreSQL later. Blobs and versions are immutable and content-addressed.
 */
public interface Store {

    /** The store format this release reads and writes. Before 1.0.0 other formats are refused, not migrated. */
    String FORMAT = "0.1";

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

    // content
    String putBlob(byte[] content);

    byte[] blob(String digest);

    void saveVersion(Model.Version version);

    Optional<Model.Version> version(String digest);

    // identities and tokens
    List<Model.Identity> identities();

    void saveIdentity(Model.Identity identity);

    List<Model.Token> tokens();

    void saveToken(Model.Token token);

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
