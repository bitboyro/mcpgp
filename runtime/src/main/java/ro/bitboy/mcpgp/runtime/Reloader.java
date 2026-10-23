package ro.bitboy.mcpgp.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.bitboy.mcpgp.runtime.audit.AuditLog;
import ro.bitboy.mcpgp.runtime.config.ConfigException;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.mcp.Ids;
import ro.bitboy.mcpgp.runtime.mcp.McpServerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Config reload (runtime-config §4.6): on a change of the config file, {@code .env} or certificate
 * files, on {@code SIGHUP}, or when {@code <server> reload} leaves a request in the storage root. The new
 * config is loaded exactly as at startup; if it doesn't load, the running config stays. Instances,
 * credentials, TLS, contract selection, errors and files change live; transport, address, port,
 * inbound auth and storage root need a restart.
 */
public final class Reloader implements AutoCloseable {

    /** The file {@code <server> reload} creates in the storage root. */
    public static final String REQUEST = "reload.request";
    private static final Logger LOG = LoggerFactory.getLogger(Reloader.class);
    private static final long DEBOUNCE_MS = 300;

    private final Startup startup;
    private final McpSyncServer server;
    private final AuditLog audit;
    private volatile McpServerFactory current;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "config-reload");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> pending;
    private WatchService watcher;
    private final Map<WatchKey, Path> directories = new HashMap<>();
    private volatile Set<Path> watched = Set.of();

    public Reloader(Startup startup, McpServerFactory factory, McpSyncServer server) {
        this.startup = startup;
        this.current = factory;
        this.server = server;
        this.audit = factory.audit();
    }

    public McpServerFactory current() {
        return current;
    }

    /** Starts watching files and, where the platform has it, SIGHUP. */
    public void start() {
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("HUP"), signal -> request("SIGHUP"));
        } catch (IllegalArgumentException | UnsupportedOperationException | NoClassDefFoundError e) {
            LOG.debug("SIGHUP is not available on this platform; use `reload` or edit the config file");
        }
        try {
            watcher = FileSystems.getDefault().newWatchService();
            watch(current.config());
            Thread thread = new Thread(this::loop, "config-watch");
            thread.setDaemon(true);
            thread.start();
        } catch (IOException e) {
            LOG.warn("Config files are not watched ({}); use `reload` after changes", e.getMessage());
        }
    }

    /** Asks for a reload; requests close together become one. */
    public synchronized void request(String trigger) {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = scheduler.schedule(() -> reload(trigger), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /** Reloads now; returns true when the new config was applied. */
    public synchronized boolean reload(String trigger) {
        RuntimeConfig loaded;
        try {
            loaded = Startup.load(startup.args(), startup.env(), startup.workingDir()).config();
        } catch (ConfigException e) {
            LOG.error("Config reload ({}) rejected; the running config stays: {}", trigger, String.join("; ", e.problems()));
            ObjectNode event = audit.event("config.reload", "error", Ids.correlation());
            event.putObject("actor").put("kind", "system");
            event.put("code", "CONFIG_INVALID");
            audit.write(event);
            return false;
        }
        return install(prepare(loaded), loaded, trigger, null);
    }

    /** Call under this reloader's lock when coordinating a managed persistence transaction. */
    public McpServerFactory prepare(RuntimeConfig config) {
        return new McpServerFactory(startup.manifest(), activeConfig(config), audit, Clock.systemUTC());
    }

    public RuntimeConfig activeConfig(RuntimeConfig desired) {
        RuntimeConfig boot = startup.config();
        List<String> pending = restartRequired(desired);
        if (pending.isEmpty()) { return desired; }
        ObjectNode document = desired.document().deepCopy();
        if (boot.document().has("server")) { document.set("server", boot.document().path("server").deepCopy()); } else { document.remove("server"); }
        document.putObject("storage").put("root", boot.storageRoot().toString());
        List<String> problems = new ArrayList<>();
        ro.bitboy.mcpgp.runtime.config.TrustedPolicies.enforce(document.path("_organization"), boot.server(), desired.instances(), document,
                startup.manifest().policies().path("rateLimits"), problems);
        if (!problems.isEmpty()) { throw new ConfigException(problems); }
        String hash = ro.bitboy.mcpgp.runtime.config.ConfigLoader.policyHash(desired.configHash(),
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(List.of(boot.configHash(), pending)));
        return new RuntimeConfig(desired.source(), document, boot.server(), desired.instances(), boot.storageRoot(), desired.audit(), desired.files(),
                desired.errorDetail(), desired.logLevel(), desired.telemetry(), desired.packs(), desired.secrets(), desired.inlineSecrets(), desired.warnings(), hash);
    }

    public RuntimeConfig previewConfig(ObjectNode document) {
        return startup.previewConfig(document);
    }

    public ro.bitboy.mcpgp.runtime.manifest.Manifest manifest() {
        return startup.manifest();
    }

    public List<String> restartRequired(RuntimeConfig desired) {
        List<String> changes = restartOnly(startup.config(), desired);
        return List.copyOf(changes);
    }

    /** Install a prepared snapshot after the source is durably persisted; caller holds this lock. */
    public synchronized boolean install(McpServerFactory next, String trigger, String administrator) {
        return install(next, next.config(), trigger, administrator);
    }
    public synchronized boolean install(McpServerFactory next, RuntimeConfig desired, String trigger, String administrator) {
        RuntimeConfig old = current.config();
        RuntimeConfig loaded = next.config();
        for (String change : restartRequired(desired)) {
            LOG.warn("Config reload: {} changed; this applies after a restart", change);
        }
        boolean toolsChanged;
        try {
            toolsChanged = current.handOver(next, server);
        } finally {
            // handOver publishes the shared dispatch snapshot before touching SDK registrations.
            current = next;
        }
        audit.reconfigure(loaded.audit());
        org.springframework.boot.logging.LoggingSystem.get(getClass().getClassLoader()).setLogLevel(null,
                org.springframework.boot.logging.LogLevel.valueOf(loaded.logLevel().toUpperCase(java.util.Locale.ROOT)));
        try {
            watch(loaded);
        } catch (IOException e) {
            LOG.warn("Config files are not watched after reload: {}", e.getMessage());
        }
        ObjectNode event = audit.event("config.reload", "ok", Ids.correlation());
        if (administrator == null) {
            event.putObject("actor").put("kind", "system");
        } else {
            event.putObject("actor").put("kind", "user").put("id", administrator).put("auth", "management");
        }
        event.put("configHash", loaded.configHash());
        audit.write(event);
        LOG.info("Config reloaded ({}); {} instance(s){}", trigger, loaded.allInstances().size(),
                toolsChanged ? "; tool list updated" : "");
        return true;
    }

    private static List<String> restartOnly(RuntimeConfig old, RuntimeConfig loaded) {
        List<String> changes = new ArrayList<>();
        RuntimeConfig.Server a = old.server();
        RuntimeConfig.Server b = loaded.server();
        if (!a.transport().equals(b.transport())) {
            changes.add("server.transport");
        }
        if (!a.host().equals(b.host()) || a.port() != b.port() || !Objects.equals(a.managementPort(), b.managementPort())) {
            changes.add("server address or port");
        }
        if (!a.basePath().equals(b.basePath())) {
            changes.add("server.basePath");
        }
        if (!a.authMode().equals(b.authMode()) || !a.auth().equals(b.auth())) {
            changes.add("server.auth");
        }
        if (!old.document().at("/server/management").equals(loaded.document().at("/server/management"))) {
            changes.add("server.management");
        }
        if (!old.storageRoot().equals(loaded.storageRoot())) {
            changes.add("storage.root");
        }
        return changes;
    }

    // ---------------------------------------------------------------------- watching

    private synchronized void watch(RuntimeConfig config) throws IOException {
        if (watcher == null) {
            return;
        }
        Set<Path> files = new HashSet<>();
        for (String key : List.of("_organizationFile", "_identityFile")) {
            if (config.document().has(key)) { files.add(Path.of(config.document().path(key).asText()).toAbsolutePath().normalize()); }
        }
        config.source().ifPresent(source -> {
            files.add(source.toAbsolutePath().normalize());
            files.add(source.toAbsolutePath().getParent().resolve(".env").normalize());
        });
        for (RuntimeConfig.Instance instance : config.allInstances()) {
            if (instance.tlsContext() != null) {
                instance.tlsContext().files().keySet().forEach(f -> files.add(f.toAbsolutePath().normalize()));
            }
        }
        Files.createDirectories(config.storageRoot());
        files.add(config.storageRoot().toAbsolutePath().resolve(REQUEST).normalize());
        Set<Path> dirs = new HashSet<>(directories.values());
        for (Path file : files) {
            Path dir = file.getParent();
            if (dir != null && Files.isDirectory(dir) && dirs.add(dir)) {
                WatchKey key = dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
                directories.put(key, dir);
            }
        }
        watched = Set.copyOf(files);
    }

    private void loop() {
        try {
            while (true) {
                WatchKey key = watcher.take();
                Path dir = directories.get(key);
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (dir == null || !(event.context() instanceof Path name)) {
                        continue;
                    }
                    Path file = dir.resolve(name).normalize();
                    if (!watched.contains(file)) {
                        continue;
                    }
                    if (name.toString().equals(REQUEST)) {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException e) {
                            // a later request will be seen anyway
                        }
                        request("reload command");
                    } else {
                        request("change of " + name);
                    }
                }
                key.reset();
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // stopping
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException e) {
                // stopping anyway
            }
        }
    }
}
