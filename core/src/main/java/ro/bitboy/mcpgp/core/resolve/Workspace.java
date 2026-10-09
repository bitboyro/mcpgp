package ro.bitboy.mcpgp.core.resolve;

import com.fasterxml.jackson.databind.JsonNode;
import ro.bitboy.mcpgp.core.diag.Codes;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.diag.Location;
import ro.bitboy.mcpgp.schemas.SchemaKind;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code mcpgp://<workspace>/<kind>/<name>[@<version>]} references to local files, using a
 * {@code mcpgp-workspace.yaml} mapping (the CLI's stand-in for the platform's shared library).
 */
public final class Workspace {

    static final Pattern REFERENCE =
            Pattern.compile("^mcpgp://([a-z0-9][a-z0-9-]*)/([a-z]+)/([A-Za-z0-9._-]+)(?:@([A-Za-z0-9._-]+))?$");

    private static final Workspace NONE = new Workspace(null, null, null);

    private final String name;
    private final Path directory;
    private final JsonNode mapping;

    private Workspace(String name, Path directory, JsonNode mapping) {
        this.name = name;
        this.directory = directory;
        this.mapping = mapping;
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
        return new Workspace(node.get().get("workspace").asText(), absolute.getParent(), node.get());
    }

    public static boolean isReference(String value) {
        return value.startsWith("mcpgp://");
    }

    /** The local file a reference points to, if this workspace maps it. */
    public Optional<Path> resolve(String reference) {
        Matcher m = REFERENCE.matcher(reference);
        if (mapping == null || !m.matches() || !m.group(1).equals(name)) {
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
