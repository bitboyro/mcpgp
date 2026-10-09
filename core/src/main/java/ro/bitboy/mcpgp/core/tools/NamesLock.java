package ro.bitboy.mcpgp.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import ro.bitboy.mcpgp.core.diag.Codes;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.diag.Location;
import ro.bitboy.mcpgp.core.resolve.Documents;
import ro.bitboy.mcpgp.schemas.SchemaKind;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** {@code mcp-names.lock}: published tool names by tool id (naming.md §7). */
public final class NamesLock {

    public static final String FILE_NAME = "mcp-names.lock";

    /** One locked entry. */
    public record Entry(String name, String since) {
    }

    private final Map<String, Entry> entries;

    private NamesLock(Map<String, Entry> entries) {
        this.entries = entries;
    }

    public static NamesLock empty() {
        return new NamesLock(Map.of());
    }

    /** Loads the lock file next to the root definition, if there is one. */
    public static NamesLock load(Path root, Schemas schemas, Diagnostics diagnostics) {
        Path file = root.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return empty();
        }
        Location location = Location.of(FILE_NAME);
        Optional<JsonNode> node = Documents.read(file, diagnostics, location);
        if (node.isEmpty()) {
            return empty();
        }
        var problems = schemas.problems(SchemaKind.NAMES_LOCK, node.get());
        if (!problems.isEmpty()) {
            problems.forEach(p -> diagnostics.error(Codes.LOCK_INVALID, p.message(), location.withPointer(p.pointer())));
            return empty();
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        node.get().path("tools").forEach(t -> entries.put(t.get("id").asText(),
                new Entry(t.get("name").asText(), t.path("since").asText(null))));
        return new NamesLock(Map.copyOf(entries));
    }

    public Optional<Entry> get(String toolId) {
        return Optional.ofNullable(entries.get(toolId));
    }

    public Map<String, Entry> entries() {
        return entries;
    }
}
