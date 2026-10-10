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
import java.util.Map;
import java.util.stream.Stream;

/**
 * Compiles a stored version with the same compiler as the CLI (spec/platform-api.md §1.2): the files
 * are written to a temporary directory, compiled, and the directory is removed.
 */
public final class Compilation {

    private final Store store;
    private final Compiler compiler = new Compiler(new Schemas());

    public Compilation(Store store) {
        this.store = store;
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
            materialize(version, dir);
            return compiler.compile(dir.resolve(version.root()), new Compiler.Options(Workspace.none(), false, build));
        } finally {
            delete(dir);
        }
    }

    /** The manifest preview of a version, cached by its digest. */
    public Preview preview(Model.Version version, String createdAt) {
        String key = version.digest();
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
