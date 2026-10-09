package ro.bitboy.mcpgp.core.resolve;

import ro.bitboy.mcpgp.core.diag.Codes;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.diag.Location;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** The path rules shared by definitions and contracts: relative, inside the project root, no remote URLs. */
public final class ProjectPaths {

    private ProjectPaths() {
    }

    public static boolean isRemote(String ref) {
        return ref.startsWith("http://") || ref.startsWith("https://");
    }

    /**
     * Resolves {@code ref}, written in file {@code from}, to an existing regular file inside {@code root}.
     * Reports why not otherwise.
     */
    public static Optional<Path> resolveLocal(String ref, Path from, Path root, Diagnostics diagnostics, Location location) {
        if (isRemote(ref)) {
            diagnostics.error(Codes.REMOTE_REFERENCE_NOT_ALLOWED,
                    "Remote reference '" + ref + "' is not allowed; commit the file to the project", location);
            return Optional.empty();
        }
        Path candidate;
        try {
            candidate = Path.of(ref);
        } catch (RuntimeException e) {
            diagnostics.error(Codes.FILE_NOT_FOUND, "Invalid path '" + ref + "'", location);
            return Optional.empty();
        }
        if (candidate.isAbsolute()) {
            diagnostics.error(Codes.PATH_OUTSIDE_ROOT, "Absolute path '" + ref + "' is not allowed; use a path relative to the project", location);
            return Optional.empty();
        }
        Path resolved = from.getParent().resolve(candidate).normalize();
        if (!resolved.startsWith(root)) {
            diagnostics.error(Codes.PATH_OUTSIDE_ROOT, "Path '" + ref + "' resolves outside the project root", location);
            return Optional.empty();
        }
        if (!Files.isRegularFile(resolved)) {
            diagnostics.error(Codes.FILE_NOT_FOUND, "File not found: '" + ref + "'", location);
            return Optional.empty();
        }
        try {
            if (!resolved.toRealPath().startsWith(root.toRealPath())) {
                diagnostics.error(Codes.PATH_OUTSIDE_ROOT, "Path '" + ref + "' leaves the project root through a link", location);
                return Optional.empty();
            }
        } catch (IOException e) {
            diagnostics.error(Codes.FILE_UNREADABLE, "Cannot read '" + ref + "': " + e.getMessage(), location);
            return Optional.empty();
        }
        return Optional.of(resolved);
    }
}
