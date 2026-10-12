package ro.bitboy.mcpgp.platform.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.manifest.CanonicalJson;
import ro.bitboy.mcpgp.core.manifest.ManifestWriter;
import ro.bitboy.mcpgp.core.resolve.Workspace;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Compiles a stored version with the same compiler as the CLI (spec/platform-api.md §1.2): the files
 * are written to a temporary directory, compiled, and the directory is removed.
 */
public final class Compilation {

    private final Store store;
    private final String workspace;
    private final Compiler compiler = new Compiler(new Schemas());

    public Compilation(Store store) {
        this(store, ro.bitboy.mcpgp.platform.PlatformConfig.DEFAULT_WORKSPACE);
    }

    /** @param workspace the workspace name of {@code mcpgp://} references this platform resolves */
    public Compilation(Store store, String workspace) {
        this.store = store;
        this.workspace = workspace;
    }

    /**
     * @param createdAt the build time recorded in the manifest (previews use the version's creation time,
     *                  so the same version always previews the same manifest)
     */
    public Compiler.Result compile(Model.Version version, ManifestWriter.Build build) {
        Path dir;
        try {
            dir = Files.createTempDirectory("mcpgp-version-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Path project = dir.resolve("project");
            materialize(version, project);
            return compiler.compile(project.resolve(version.root()), new Compiler.Options(workspace(dir.resolve("workspace")), false, build));
        } finally {
            delete(dir);
        }
    }

    /**
     * {@code mcpgp://<workspace>/<kind>/<name>@<version>} from the library, and
     * {@code mcpgp://<workspace>/servers/<id>@<version>} from the servers; written below {@code dir}
     * when a compilation asks for them.
     */
    private Workspace workspace(Path dir) {
        return Workspace.of(workspace, reference -> {
            List<Model.VersionRef> versions;
            if (reference.kind().equals("servers")) {
                versions = store.server(safe(reference.name())).map(Model.Server::versions).orElse(List.of());
            } else {
                versions = store.libraryItem(reference.kind(), safe(reference.name())).map(Model.LibraryItem::versions).orElse(List.of());
            }
            Optional<Model.VersionRef> ref = reference.version() == null
                    ? (versions.isEmpty() ? Optional.empty() : Optional.of(versions.get(versions.size() - 1)))
                    : versions.stream().filter(v -> String.valueOf(v.number()).equals(reference.version())).findFirst();
            if (ref.isEmpty()) {
                return Optional.empty();
            }
            Model.Version files = store.version(ref.get().digest()).orElseThrow();
            Path target = dir.resolve(reference.kind()).resolve(reference.name() + "@" + ref.get().number());
            if (!Files.exists(target)) {
                materialize(files, target);
            }
            return Optional.of(target.resolve(files.root()));
        });
    }

    private static String safe(String name) {
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) {
            throw new IllegalArgumentException("invalid reference name");
        }
        return name;
    }

    /** The manifest preview of a version, cached by its digest and the state of the library and servers it may use. */
    public Preview preview(Model.Version version, String createdAt) {
        String key = key(version);
        var cached = store.cached("manifest", key);
        if (cached.isPresent()) {
            return new Preview(new String(cached.get(), StandardCharsets.UTF_8), null);
        }
        Compiler.Result result = compiled(key, version, createdAt);
        if (result.manifest().isEmpty()) {
            return new Preview(null, result);
        }
        ObjectNode manifest = result.manifest().get();
        String text = CanonicalJson.pretty(manifest);
        store.cache("manifest", key, text.getBytes(StandardCharsets.UTF_8));
        return new Preview(text, result);
    }

    /** Every operation and what became of it ({@link Operations}), cached like the preview; null when the version doesn't compile. */
    public ObjectNode operations(Model.Version version, String createdAt) {
        return derived("operations", version, createdAt, result -> Operations.of(result));
    }

    /** The version's naming ({@link NamingView}), cached like the preview; null when the version doesn't compile. */
    public ObjectNode naming(Model.Version version, String createdAt) {
        return derived("naming", version, createdAt, result -> NamingView.of(result, version, store));
    }

    /**
     * What a build of the version will contain (packaging.md): formats, files and the generated docs,
     * without building it; null when the version doesn't compile.
     */
    public ObjectNode packagePreview(Model.Version version, String createdAt, boolean signed) {
        return derived("package-" + (signed ? "signed" : "unsigned"), version, createdAt, result -> {
            try {
                ro.bitboy.mcpgp.packager.Packager.Preview preview = ro.bitboy.mcpgp.packager.Packager.preview(result.manifest().orElseThrow(),
                        result.definition().orElseThrow().document(), signed);
                return (ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(preview);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * The definition as data: {@code root} (the root file as written) and {@code resolved} (with what it
     * extends and includes merged in); null when the version doesn't compile.
     */
    public ObjectNode definition(Model.Version version, String createdAt) {
        return derived("definition", version, createdAt, result -> {
            ObjectNode answer = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            try {
                answer.set("root", new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                        .readTree(store.blob(version.files().get(version.root()))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            answer.set("resolved", result.definition().orElseThrow().document().deepCopy());
            // notices from descriptions.include: id → the shared item they come from
            com.fasterxml.jackson.databind.node.ObjectNode sources = answer.putObject("noticeSources");
            result.definition().get().noticeSources().forEach(sources::put);
            return answer;
        });
    }

    private ObjectNode derived(String kind, Model.Version version, String createdAt, java.util.function.Function<Compiler.Result, ObjectNode> from) {
        String key = key(version);
        var cached = store.cached(kind, key);
        if (cached.isPresent()) {
            try {
                return (ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(cached.get());
            } catch (IOException e) {
                // compile again
            }
        }
        Compiler.Result result = compiled(key, version, createdAt);
        if (!result.ok() || result.definition().isEmpty()) {
            return null;
        }
        ObjectNode value = from.apply(result);
        store.cache(kind, key, CanonicalJson.compact(value).getBytes(StandardCharsets.UTF_8));
        return value;
    }

    /**
     * The last few compilations, by {@link #key}: the preview, the operations, the naming and the package
     * of one version come from one compile, and requests for a version being compiled wait for it
     * instead of compiling it again.
     */
    private final Map<String, java.util.concurrent.CompletableFuture<Compiler.Result>> results = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, java.util.concurrent.CompletableFuture<Compiler.Result>> eldest) {
            return size() > 3; // a big server's result is tens of MB
        }
    };

    private Compiler.Result compiled(String key, Model.Version version, String createdAt) {
        java.util.concurrent.CompletableFuture<Compiler.Result> mine = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<Compiler.Result> running;
        synchronized (results) {
            running = results.putIfAbsent(key, mine);
        }
        if (running != null) {
            try {
                return running.join();
            } catch (java.util.concurrent.CompletionException e) {
                // that compile failed: compile here
            }
        }
        long start = System.nanoTime();
        try {
            Compiler.Result result = compile(version, new ManifestWriter.Build(Instant.parse(createdAt), Compiler.VERSION, null, null, null));
            mine.complete(result);
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms > 2000) {
                LOG.warn("compiling version {} took {} ms", version.digest().substring(0, Math.min(12, version.digest().length())), ms);
            }
            return result;
        } catch (RuntimeException e) {
            synchronized (results) {
                results.remove(key, mine);
            }
            mine.completeExceptionally(e);
            throw e;
        }
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Compilation.class);
    private static final java.util.regex.Pattern REFERENCE = java.util.regex.Pattern.compile("mcpgp://[^/\\s'\"]+/([a-z]+)/([A-Za-z0-9._-]+)");

    /**
     * What a version's derived data is cached under: its files, and the latest versions of the library
     * items and servers its {@code mcpgp://} references reach (through each other too). Saving a server
     * doesn't change the key of its own versions, nor of servers that don't use it.
     */
    private String key(Model.Version version) {
        java.util.Set<String> seen = new java.util.TreeSet<>();
        StringBuilder state = new StringBuilder(version.digest()).append('\n');
        java.util.ArrayDeque<Model.Version> queue = new java.util.ArrayDeque<>(List.of(version));
        while (!queue.isEmpty()) {
            Model.Version v = queue.poll();
            for (Map.Entry<String, String> file : v.files().entrySet()) {
                if (file.getKey().startsWith("contracts/")) {
                    continue; // specs can't hold references
                }
                java.util.regex.Matcher m = REFERENCE.matcher(new String(store.blob(file.getValue()), StandardCharsets.UTF_8));
                while (m.find()) {
                    String kind = m.group(1);
                    String name = m.group(2);
                    if (!seen.add(kind + "/" + name)) {
                        continue;
                    }
                    Optional<Model.VersionRef> latest = kind.equals("servers")
                            ? store.server(name).map(Model.Server::latest)
                            : store.libraryItem(kind, name).map(Model.LibraryItem::latest);
                    state.append(kind).append('/').append(name).append('@').append(latest.map(Model.VersionRef::digest).orElse("")).append('\n');
                    latest.flatMap(l -> store.version(l.digest())).ifPresent(queue::add);
                }
            }
        }
        return ro.bitboy.mcpgp.platform.store.FileStore.sha256(state.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    /** A manifest (when the version compiles) and the compiler result (null when served from the cache). */
    public record Preview(String manifest, Compiler.Result result) {
    }

    /** Writes a version's files under {@code dir}. */
    public void materialize(Model.Version version, Path dir) {
        for (Map.Entry<String, String> file : version.files().entrySet()) {
            Path target = dir.resolve(file.getKey()).normalize();
            if (!target.startsWith(dir)) {
                throw new IllegalStateException("path outside the project in a stored version");
            }
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, store.blob(file.getValue()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static void delete(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // temporary files; the OS cleans them eventually
        }
    }
}
