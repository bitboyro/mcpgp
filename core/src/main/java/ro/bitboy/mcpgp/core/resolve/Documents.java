package ro.bitboy.mcpgp.core.resolve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import ro.bitboy.mcpgp.core.diag.Codes;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.diag.Location;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Reads YAML or JSON files into Jackson trees, reporting problems as diagnostics. */
public final class Documents {

    public static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    public static final ObjectMapper JSON = new ObjectMapper();

    private Documents() {
    }

    public static Optional<JsonNode> read(Path file, Diagnostics diagnostics, Location location) {
        if (!Files.isRegularFile(file)) {
            diagnostics.error(Codes.FILE_NOT_FOUND, "File not found: " + location.file(), location);
            return Optional.empty();
        }
        ObjectMapper mapper = file.getFileName().toString().endsWith(".json") ? JSON : YAML;
        try {
            JsonNode node = mapper.readTree(file.toFile());
            if (node == null || node.isMissingNode()) {
                diagnostics.error(Codes.FILE_UNREADABLE, "File is empty", location);
                return Optional.empty();
            }
            return Optional.of(node);
        } catch (IOException e) {
            diagnostics.error(Codes.FILE_UNREADABLE, "Cannot parse file: " + firstLine(e.getMessage()), location);
            return Optional.empty();
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
