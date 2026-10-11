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
        String key = version.digest() + "-" + libraryState();
        var cached = store.cached("manifest", key);
        if (cached.isPresent()) {
            return new Preview(new String(cached.get(), StandardCharsets.UTF_8), null);
        }
        Compiler.Result result = compile(version, new ManifestWriter.Build(Instant.parse(createdAt), Compiler.VERSION, null, null, null));
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
            return answer;
        });
    }

    private ObjectNode derived(String kind, Model.Version version, String createdAt, java.util.function.Function<Compiler.Result, ObjectNode> from) {
        String key = version.digest() + "-" + libraryState();
        var cached = store.cached(kind, key);
        if (cached.isPresent()) {
            try {
                return (ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(cached.get());
            } catch (IOException e) {
                // compile again
            }
        }
        Compiler.Result result = compile(version, new ManifestWriter.Build(Instant.parse(createdAt), Compiler.VERSION, null, null, null));
        if (!result.ok() || result.definition().isEmpty()) {
            return null;
        }
        ObjectNode value = from.apply(result);
        store.cache(kind, key, CanonicalJson.compact(value).getBytes(StandardCharsets.UTF_8));
        return value;
    }

    /** Changes whenever a library item or server gets a new version (a reference may then resolve differently). */
    private String libraryState() {
        StringBuilder state = new StringBuilder();
        store.library().forEach(i -> state.append(i.kind()).append('/').append(i.name()).append('@').append(i.latest().digest()).append('\n'));
        store.servers().forEach(s -> state.append("servers/").append(s.id()).append('@')
                .append(s.latest() == null ? "" : s.latest().digest()).append('\n'));
        return ro.bitboy.mcpgp.platform.store.FileStore.sha256(state.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
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
