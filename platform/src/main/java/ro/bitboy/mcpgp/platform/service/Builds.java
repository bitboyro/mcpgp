package ro.bitboy.mcpgp.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.core.manifest.ManifestWriter;
import ro.bitboy.mcpgp.packager.Minisign;
import ro.bitboy.mcpgp.packager.Packager;
import ro.bitboy.mcpgp.platform.PlatformConfig;
import ro.bitboy.mcpgp.platform.api.ApiException;
import ro.bitboy.mcpgp.platform.store.FileStore;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds (spec/platform-api.md §2.2): queued, then compiled and packaged one at a time with the same
 * compiler and packager as the CLI; the package is signed with the platform's key. Also release,
 * archive / restore / delete and retention.
 */
public final class Builds implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Builds.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern REFERENCE = Pattern.compile("^\\$\\{(env|file):([^}]+)}$");

    private final Store store;
    private final Compilation compilation;
    private final PlatformConfig config;
    private final Map<String, String> env;
    private final Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "builds");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService retention = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "retention");
        thread.setDaemon(true);
        return thread;
    });

    public Builds(Store store, Compilation compilation, PlatformConfig config, Map<String, String> env, Clock clock) {
        this.store = store;
        this.compilation = compilation;
        this.config = config;
        this.env = env;
        this.clock = clock;
    }

    /** Marks builds left over from a stopped platform as failed, and schedules retention daily. */
    public void start() {
        for (Model.Build build : store.builds()) {
            if (build.status().equals("queued") || build.status().equals("running")) {
                save(with(build, "failed", build.started(), now(), build.serverVersion(), build.artifacts(), "the platform stopped during the build"));
            }
        }
        retention.scheduleAtFixedRate(() -> {
            try {
                applyRetention();
            } catch (RuntimeException e) {
                LOG.error("Retention failed", e);
            }
        }, 1, Duration.ofDays(1).toMinutes(), TimeUnit.MINUTES);
    }

    public Model.Build queue(Model.Server server, Model.VersionRef version, Integer buildNumber, String versionOverride, Access.Principal who) {
        if (server.archived()) {
            throw new ApiException(409, "CONFLICT", "server " + server.id() + " is archived");
        }
        String id = "b-" + HexFormat.of().formatHex(bytes(8));
        Model.Build build = new Model.Build(id, server.id(), version.number(), version.digest(), "queued", who.identity().name(),
                who.identity().kind(), now(), null, null, buildNumber, null, false, null, false, null, false, List.of(), null);
        store.saveBuild(build);
        record(who.identity().name(), who.identity().kind(), "build.start", id, "ok", Map.of("server", server.id(), "version", version.number()));
        worker.submit(() -> run(id, versionOverride));
        return build;
    }

    private void run(String id, String versionOverride) {
        String started = now();
        Model.Build build = with(store.build(id).orElseThrow(), "running", started, null, null, List.of(), null);
        save(build);
        log(id, "building " + build.server() + " version " + build.version() + " (sha256:" + build.versionDigest() + ")");
        Path work = null;
        try {
            Model.Version version = store.version(build.versionDigest()).orElseThrow();
            Instant createdAt = Instant.parse(started);
            // the build number: given, or the count of earlier builds of the same server version (cli.md §5)
            Compiler.Result result = compilation.compile(version, new ManifestWriter.Build(createdAt, Compiler.VERSION, versionOverride,
                    build.buildNumber(), "mcpgp-platform:" + build.server() + "@" + build.version()));
            if (result.ok() && build.buildNumber() == null && versionOverride == null) {
                String base = result.manifest().get().at("/server/version").asText();
                long earlier = store.builds().stream().filter(b -> b.server().equals(build.server()) && !b.id().equals(id)
                        && b.status().equals("succeeded") && b.serverVersion() != null && b.serverVersion().split("\\+")[0].equals(base)).count();
                if (earlier > 0) {
                    int number = (int) earlier + 1;
                    log(id, "server version " + base + " was built before: build number " + number);
                    result = compilation.compile(version, new ManifestWriter.Build(createdAt, Compiler.VERSION, null, number,
                            "mcpgp-platform:" + build.server() + "@" + build.version()));
                }
            }
            for (Diagnostic d : result.diagnostics()) {
                if (d.level() != Diagnostic.Level.INFO) {
                    log(id, d.toString());
                }
            }
            if (!result.ok()) {
                finish(id, "failed", null, List.of(), "the definition does not compile; see the log");
                return;
            }
            Minisign.Keys signing = signingKey(result.definition().get().document());
            work = Files.createTempDirectory("mcpgp-build-");
            JsonNode document = result.definition().get().document();
            Packager.Result packaged = new Packager(Packager::runtimeJar).build(result.manifest().get(), document, createdAt, work, signing);
            packaged.warnings().forEach(w -> log(id, "warning: " + w));
            List<Model.Artifact> artifacts = new ArrayList<>();
            for (Path file : List.of(packaged.zip(), packaged.manifest())) {
                byte[] content = Files.readAllBytes(file);
                store.writeBuildFile(id, file.getFileName().toString(), content);
                artifacts.add(new Model.Artifact(file.getFileName().toString(), content.length, FileStore.sha256(content)));
            }
            String serverVersion = result.manifest().get().at("/server/version").asText();
            log(id, "built " + packaged.zip().getFileName() + (signing == null ? " (unsigned)" : " (signed)"));
            finish(id, "succeeded", serverVersion, artifacts, null);
        } catch (ApiException e) {
            log(id, "error: " + e.getMessage());
            finish(id, "failed", null, List.of(), e.getMessage());
        } catch (IOException | RuntimeException e) {
            LOG.error("Build {} failed", id, e);
            log(id, "error: the build failed (" + e.getClass().getSimpleName() + "); see the platform log");
            finish(id, "failed", null, List.of(), "internal error");
        } finally {
            if (work != null) {
                Compilation.delete(work);
            }
        }
    }

    private void finish(String id, String status, String serverVersion, List<Model.Artifact> artifacts, String error) {
        Model.Build build = store.build(id).orElseThrow();
        Model.Build done = with(build, status, build.started(), now(), serverVersion, artifacts, error);
        save(done);
        log(id, status);
        record(build.startedBy(), build.startedByKind(), "build.finish", id, status.equals("succeeded") ? "ok" : "error",
                serverVersion == null ? Map.of() : Map.of("serverVersion", serverVersion));
    }

    /**
     * The platform's signing key (spec/platform-api.md §2.2): {@code signing.key} in the platform config,
     * a reference. A definition that asks for signing fails without one; others are built unsigned.
     */
    private Minisign.Keys signingKey(JsonNode definition) {
        if (config.signingKey() == null) {
            if (definition.at("/build/sign").isObject()) {
                throw new ApiException(422, "VALIDATION", "the definition asks for a signed package, but the platform has no signing.key");
            }
            return null;
        }
        Matcher ref = REFERENCE.matcher(config.signingKey());
        if (!ref.matches()) {
            throw new ApiException(500, "INTERNAL", "signing.key must be a reference (${env:…} or ${file:…})");
        }
        String text;
        if (ref.group(1).equals("env")) {
            text = env.get(ref.group(2));
        } else {
            try {
                text = Files.readString(Path.of(ref.group(2)));
            } catch (IOException e) {
                text = null;
            }
        }
        if (text == null || text.isBlank()) {
            throw new ApiException(500, "INTERNAL", "the platform's signing key (" + config.signingKey() + ") can't be read");
        }
        return Minisign.readSecretKey(text);
    }

    // ---------------------------------------------------------------------- release, archive, delete

    public Model.Build release(Model.Build build, String by, String kind) {
        if (!build.status().equals("succeeded") || build.deleted()) {
            throw new ApiException(409, "CONFLICT", "only succeeded builds with files can be released");
        }
        Model.Build released = new Model.Build(build.id(), build.server(), build.version(), build.versionDigest(), build.status(),
                build.startedBy(), build.startedByKind(), build.created(), build.started(), build.finished(), build.buildNumber(),
                build.serverVersion(), true, by, build.archived(), build.archivedAt(), build.deleted(), build.artifacts(), build.error());
        save(released);
        record(by, kind, "build.release", build.id(), "ok", Map.of("serverVersion", build.serverVersion()));
        return released;
    }

    public Model.Build archive(Model.Build build, boolean archived, String by, String kind) {
        if (build.status().equals("queued") || build.status().equals("running")) {
            throw new ApiException(409, "CONFLICT", "the build is still " + build.status());
        }
        Model.Build changed = new Model.Build(build.id(), build.server(), build.version(), build.versionDigest(), build.status(),
                build.startedBy(), build.startedByKind(), build.created(), build.started(), build.finished(), build.buildNumber(),
                build.serverVersion(), build.released(), build.releasedBy(), archived, archived ? now() : null, build.deleted(),
                build.artifacts(), build.error());
        save(changed);
        record(by, kind, archived ? "build.archive" : "build.restore", build.id(), "ok", Map.of());
        return changed;
    }

    /** Removes the files; the record and its activity stay. Released builds must be archived first. */
    public Model.Build delete(Model.Build build, String by, String kind) {
        if (build.released() && !build.archived()) {
            throw new ApiException(409, "CONFLICT", "released builds must be archived before they can be deleted");
        }
        if (build.status().equals("queued") || build.status().equals("running")) {
            throw new ApiException(409, "CONFLICT", "the build is still " + build.status());
        }
        store.deleteBuildFiles(build.id());
        Model.Build deleted = new Model.Build(build.id(), build.server(), build.version(), build.versionDigest(), build.status(),
                build.startedBy(), build.startedByKind(), build.created(), build.started(), build.finished(), build.buildNumber(),
                build.serverVersion(), build.released(), build.releasedBy(), build.archived(), build.archivedAt(), true, List.of(),
                build.error());
        save(deleted);
        record(by, kind, "build.delete", build.id(), "ok", Map.of());
        return deleted;
    }

    /**
     * Retention (spec/platform-api.md §2.2): archive builds older than {@code archiveAfterDays}, delete the
     * files of builds archived longer than {@code deleteArchivedAfterDays}; the last {@code keepLast} builds
     * per server and released builds are never touched.
     */
    public void applyRetention() {
        PlatformConfig.Retention r = config.retention();
        Instant now = clock.instant();
        Map<String, List<Model.Build>> byServer = new java.util.HashMap<>();
        store.builds().forEach(b -> byServer.computeIfAbsent(b.server(), k -> new ArrayList<>()).add(b));
        for (List<Model.Build> builds : byServer.values()) {
            builds.sort(Comparator.comparing(Model.Build::created).reversed());
            for (int i = r.keepLast(); i < builds.size(); i++) {
                Model.Build b = builds.get(i);
                if (b.released() || b.status().equals("queued") || b.status().equals("running")) {
                    continue;
                }
                if (!b.archived() && Instant.parse(b.created()).isBefore(now.minus(Duration.ofDays(r.archiveAfterDays())))) {
                    archive(b, true, "retention", "system");
                } else if (b.archived() && !b.deleted() && b.archivedAt() != null
                        && Instant.parse(b.archivedAt()).isBefore(now.minus(Duration.ofDays(r.deleteArchivedAfterDays())))) {
                    delete(b, "retention", "system");
                }
            }
        }
    }

    // ---------------------------------------------------------------------- helpers

    private static Model.Build with(Model.Build b, String status, String started, String finished, String serverVersion,
                                    List<Model.Artifact> artifacts, String error) {
        return new Model.Build(b.id(), b.server(), b.version(), b.versionDigest(), status, b.startedBy(), b.startedByKind(), b.created(),
                started, finished, b.buildNumber(), serverVersion, b.released(), b.releasedBy(), b.archived(), b.archivedAt(), b.deleted(),
                artifacts, error);
    }

    private void save(Model.Build build) {
        store.saveBuild(build);
    }

    private void log(String id, String line) {
        store.appendBuildLog(id, now() + "  " + line + "\n");
    }

    private void record(String actor, String kind, String action, String target, String outcome, Map<String, Object> details) {
        store.append(new Model.Activity(now(), actor, kind, action, target, outcome, details));
    }

    private String now() {
        return clock.instant().toString();
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    @Override
    public void close() {
        worker.shutdownNow();
        retention.shutdownNow();
    }

    /** Text of a build's log so far. */
    public String logText(String id) {
        return store.buildFile(id, "log.txt").map(b -> new String(b, StandardCharsets.UTF_8)).orElse("");
    }
}
