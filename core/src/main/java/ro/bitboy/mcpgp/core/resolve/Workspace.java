package ro.bitboy.mcpgp.core.resolve;

import com.fasterxml.jackson.databind.JsonNode;
import ro.bitboy.mcpgp.core.diag.Codes;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.diag.Location;
import ro.bitboy.mcpgp.schemas.SchemaKind;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code mcpgp://<workspace>/<kind>/<name>[@<version>]} references to local files, using a
 * {@code mcpgp-workspace.yaml} mapping (the CLI's stand-in for the platform's shared library).
 */
public final class Workspace {

    static final Pattern REFERENCE =
            Pattern.compile("^mcpgp://([a-z0-9][a-z0-9-]*)/([a-z]+)/([A-Za-z0-9._-]+)(?:@([A-Za-z0-9._-]+))?$");

    private static final Workspace NONE = new Workspace(null, null, null, null);

    /** A parsed reference: kind ({@code servers}, {@code naming}, …), name and version ({@code null} = latest). */
    public record Reference(String kind, String name, String version) {
    }

    private final String name;
    private final Path directory;
    private final JsonNode mapping;
    private final Function<Reference, Optional<Path>> lookup;

    private Workspace(String name, Path directory, JsonNode mapping, Function<Reference, Optional<Path>> lookup) {
        this.name = name;
        this.directory = directory;
        this.mapping = mapping;
        this.lookup = lookup;
    }

    /**
     * A workspace backed by a lookup (the platform's library): {@code lookup} returns the local file of
     * a reference, or empty when there is no such item.
     */
    public static Workspace of(String name, Function<Reference, Optional<Path>> lookup) {
        return new Workspace(name, null, null, lookup);
    }

    public static Workspace none() {
        return NONE;
    }

    /** Loads a workspace mapping file; reports problems and returns {@link #none()} if it can't be used. */
    public static Workspace load(Path file, Schemas schemas, Diagnostics diagnostics) {
        Path absolute = file.toAbsolutePath().normalize();
        Location location = Location.of(absolute.getFileName().toString());
        Optional<JsonNode> node = Documents.read(absolute, diagnostics, location);
        if (node.isEmpty()) {
            return NONE;
        }
        var problems = schemas.problems(SchemaKind.WORKSPACE, node.get());
        if (!problems.isEmpty()) {
            problems.forEach(p -> diagnostics.error(Codes.WORKSPACE_INVALID, p.message(), location.withPointer(p.pointer())));
            return NONE;
        }
        return new Workspace(node.get().get("workspace").asText(), absolute.getParent(), node.get(), null);
    }

    public static boolean isReference(String value) {
        return value.startsWith("mcpgp://");
    }

    /** The local file a reference points to, if this workspace maps it. */
    public Optional<Path> resolve(String reference) {
        Matcher m = REFERENCE.matcher(reference);
        if (!m.matches() || name == null || !m.group(1).equals(name)) {
            return Optional.empty();
        }
        if (lookup != null) {
            String version = m.group(4) == null || m.group(4).equals("latest") ? null : m.group(4);
            return lookup.apply(new Reference(m.group(2), m.group(3), version));
        }
        if (mapping == null) {
            return Optional.empty();
        }
        JsonNode kind = mapping.get(m.group(2));
        if (kind == null) {
            return Optional.empty();
        }
        String entry = m.group(3);
        String version = m.group(4);
        JsonNode target = null;
        if (version != null && !version.equals("latest")) {
            target = kind.get(entry + "@" + version);
        }
        if (target == null) {
            target = kind.get(entry);
        }
        return target == null ? Optional.empty() : Optional.of(directory.resolve(target.asText()).normalize());
    }
}
