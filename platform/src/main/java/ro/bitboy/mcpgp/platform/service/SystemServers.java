package ro.bitboy.mcpgp.platform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The platform's own MCP server as a server of the platform (spec/platform-api.md §2.1): the first
 * server people see, made from the project bundled in this build. At every start it is created when
 * missing and gets a new version when this build's project differs from its latest one, so an upgrade
 * adds one version and a restart adds none. Nobody else changes it ({@link Servers#writable}); a
 * variant holds a company's changes and follows its new versions.
 */
public final class SystemServers {

    /** The id of the platform's own server. */
    public static final String PLATFORM = "mcpgp-platform";
    static final String TITLE = "mcpgp platform";
    static final String ACTOR = "system";

    private static final Logger LOG = LoggerFactory.getLogger(SystemServers.class);

    private SystemServers() {
    }

    /** What happened at start: {@code created}, {@code updated}, {@code unchanged}, {@code archived}, {@code restored} or {@code skipped}. */
    public static String ensure(Store store, boolean enabled) {
        Optional<Model.Server> current = store.server(PLATFORM);
        if (current.isPresent() && !current.get().system()) {
            LOG.warn("a server called {} exists and wasn't made by the platform; the platform's own server is left out", PLATFORM);
            return "skipped";
        }
        if (!enabled) {
            return current.isPresent() && !current.get().archived() ? setArchived(store, true) : "skipped";
        }
        Model.Version version = new Projects(store).store(files());
        String message = "mcpgp " + Compiler.VERSION;
        // several platforms on one store may start at once: one write wins, the others see it
        for (int attempt = 0; attempt < 5; attempt++) {
            Model.Server server = store.server(PLATFORM).orElse(null);
            String now = Instant.now().toString();
            try {
                if (server == null) {
                    store.saveServer(new Model.Server(PLATFORM, TITLE, null, false, now, now,
                            List.of(new Model.VersionRef(1, version.digest(), message, ACTOR, now)), 0, true), -1);
                    record(store, "server.create", Map.of("version", 1, "digest", version.digest()));
                    return "created";
                }
                boolean same = server.latest() != null && server.latest().digest().equals(version.digest());
                if (same && !server.archived()) {
                    return "unchanged";
                }
                List<Model.VersionRef> versions = new ArrayList<>(server.versions());
                if (!same) {
                    versions.add(new Model.VersionRef(server.latest() == null ? 1 : server.latest().number() + 1, version.digest(), message, ACTOR, now));
                }
                store.saveServer(new Model.Server(PLATFORM, server.title(), null, false, server.created(), now, versions, server.revision(), true),
                        server.revision());
                if (same) {
                    record(store, "server.restore", Map.of());
                    return "restored";
                }
                record(store, "version.create", Map.of("version", versions.getLast().number(), "digest", version.digest()));
                return "updated";
            } catch (Store.ConflictException e) {
                // changed meanwhile: read it again
            }
        }
        throw new IllegalStateException("the platform's own server " + PLATFORM + " kept changing while it was being updated");
    }

    private static String setArchived(Store store, boolean archived) {
        Model.Server server = store.server(PLATFORM).orElseThrow();
        try {
            store.saveServer(new Model.Server(PLATFORM, server.title(), null, archived, server.created(), Instant.now().toString(),
                    server.versions(), server.revision(), true), server.revision());
        } catch (Store.ConflictException e) {
            return "skipped";
        }
        record(store, "server.archive", Map.of("reason", "platformServer.enabled: false"));
        return "archived";
    }

    /** The project bundled in this build, from platform/mcp. */
    static Map<String, byte[]> files() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String file : Builds.PLATFORM_MCP_FILES) {
            try (InputStream in = SystemServers.class.getResourceAsStream(Builds.PLATFORM_MCP + file)) {
                if (in == null) {
                    throw new IllegalStateException("the platform MCP server's " + file + " is not in this build");
                }
                files.put(file, in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    private static void record(Store store, String action, Map<String, Object> details) {
        store.append(new Model.Activity(Instant.now().toString(), ACTOR, ACTOR, action, PLATFORM, "ok", details));
    }
}
