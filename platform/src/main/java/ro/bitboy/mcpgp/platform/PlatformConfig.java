package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code platform.yaml} (spec/platform-api.md §7): where the store lives and where the API listens.
 * Environment variables {@code MCPGP_PLATFORM_STORAGE__ROOT}, {@code MCPGP_PLATFORM_HTTP__HOST} and
 * {@code MCPGP_PLATFORM_HTTP__PORT} override the file.
 */
public record PlatformConfig(Path storageRoot, String host, int port, String signingKey, Retention retention) {

    /** Build retention (spec/platform-api.md §2.2); released builds and the last {@code keepLast} are never touched. */
    public record Retention(int archiveAfterDays, int deleteArchivedAfterDays, int keepLast) {
        public static final Retention DEFAULT = new Retention(90, 365, 10);
    }

    public PlatformConfig(Path storageRoot, String host, int port) {
        this(storageRoot, host, port, null, Retention.DEFAULT);
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public static PlatformConfig load(Path file, Map<String, String> env) {
        JsonNode doc = YAML.createObjectNode();
        if (file != null) {
            try {
                JsonNode read = YAML.readTree(file.toFile());
                if (read != null && read.isObject()) {
                    doc = read;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + file, e);
            }
        }
        String type = doc.at("/storage/type").asText("files");
        if (!type.equals("files")) {
            throw new IllegalArgumentException("storage.type " + type + " is not available yet; use files");
        }
        String root = env.getOrDefault("MCPGP_PLATFORM_STORAGE__ROOT",
                doc.at("/storage/root").asText(Path.of(System.getProperty("user.home"), ".mcpgp-platform").toString()));
        String host = env.getOrDefault("MCPGP_PLATFORM_HTTP__HOST", doc.at("/http/host").asText("127.0.0.1"));
        int port = Integer.parseInt(env.getOrDefault("MCPGP_PLATFORM_HTTP__PORT", doc.at("/http/port").asText("8443")));
        Path storage = Path.of(root);
        if (!storage.isAbsolute() && file != null) {
            storage = file.toAbsolutePath().getParent().resolve(storage).normalize();
        }
        String signing = env.getOrDefault("MCPGP_PLATFORM_SIGNING__KEY", doc.at("/signing/key").asText(null));
        JsonNode retention = doc.path("retention");
        Retention r = new Retention(retention.path("archiveAfterDays").asInt(Retention.DEFAULT.archiveAfterDays()),
                retention.path("deleteArchivedAfterDays").asInt(Retention.DEFAULT.deleteArchivedAfterDays()),
                retention.path("keepLast").asInt(Retention.DEFAULT.keepLast()));
        return new PlatformConfig(storage, host, port, signing, r);
    }

    static Path configFile(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--config")) {
                return Path.of(args[i + 1]);
            }
        }
        Path local = Path.of("platform.yaml");
        return Files.exists(local) ? local : null;
    }
}
