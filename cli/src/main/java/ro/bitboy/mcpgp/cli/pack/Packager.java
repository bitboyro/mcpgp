package ro.bitboy.mcpgp.cli.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.core.manifest.CanonicalJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Builds the package (packaging.md §1): {@code <server>-<version>.zip} with the server jar (the runtime
 * with this build's manifest embedded at {@code /mcpgp/manifest.json}), a copy of the manifest, the
 * build's config schema, an example config, and {@code SHA256SUMS}. The same inputs and build time
 * give a byte-identical zip.
 */
public final class Packager {

    /** Names the runtime jar the CLI carries (see {@link #runtimeJar()}). */
    static final String RUNTIME_PROPERTIES = "/mcpgp/runtime.properties";
    /** Where the server jar carries its manifest (Manifest.CLASSPATH_LOCATION inside Spring Boot's layout). */
    public static final String MANIFEST_ENTRY = "BOOT-INF/classes/mcpgp/manifest.json";
    private static final List<String> TARGETS = List.of("jar");

    /** What was built, and what was asked for but isn't available yet. */
    public record Result(Path zip, Path manifest, List<String> warnings) {
    }

    private final InputStreamSource runtime;

    @FunctionalInterface
    public interface InputStreamSource {
        InputStream open() throws IOException;
    }

    /** The runtime jar carried by this CLI, as a stream (null when the CLI was built without it). */
    public static InputStream runtimeJar() throws IOException {
        java.util.Properties properties = new java.util.Properties();
        try (InputStream in = Packager.class.getResourceAsStream(RUNTIME_PROPERTIES)) {
            if (in == null) {
                return null;
            }
            properties.load(in);
        }
        return Packager.class.getResourceAsStream(properties.getProperty("jar", ""));
    }

    /** @param runtime the runtime jar (normally {@link #runtimeJar()}) */
    public Packager(InputStreamSource runtime) {
        this.runtime = runtime;
    }

    /**
     * @param definition the resolved definition (for {@code build.targets} and {@code generation})
     * @param out        the output directory ({@code dist/})
     */
    public Result build(ObjectNode manifest, JsonNode definition, Instant time, Path out) throws IOException {
        String server = manifest.at("/server/name").asText();
        String version = manifest.at("/server/version").asText();
        String base = server + "-" + version;
        List<String> warnings = new ArrayList<>();
        for (JsonNode target : definition.at("/build/targets")) {
            if (!TARGETS.contains(target.asText())) {
                warnings.add("build target " + target.asText() + " is not available in this version; the package has the jar only");
            }
        }
        String mode = definition.at("/generation/mode").asText("runtime");
        if (!mode.equals("runtime")) {
            warnings.add("generation.mode " + mode + " is not available in this version; the server works from its manifest");
        }

        Files.createDirectories(out);
        Path staging = Files.createTempDirectory(out, ".staging-");
        try {
            String manifestText = CanonicalJson.pretty(manifest);
            Map<String, Path> files = new LinkedHashMap<>();

            Path runtimeJar = staging.resolve(".runtime.jar");
            try (InputStream in = runtime.open()) {
                if (in == null) {
                    throw new IOException("this CLI was built without the server runtime jar (see " + RUNTIME_PROPERTIES + ")");
                }
                Files.copy(in, runtimeJar);
            }
            Path jar = staging.resolve(server + ".jar");
            Zips.copyWith(runtimeJar, Map.of(MANIFEST_ENTRY, manifestText.getBytes(StandardCharsets.UTF_8)), time, jar);
            Files.delete(runtimeJar);
            files.put(server + ".jar", jar);

            files.put("manifest.json", write(staging, "manifest.json", manifestText));
            files.put("server.config.schema.json", write(staging, "server.config.schema.json",
                    CanonicalJson.pretty(ConfigSchema.generate(manifest))));
            files.put(server + ".example.yaml", write(staging, server + ".example.yaml", ExampleConfig.yaml(manifest)));

            files.put("SHA256SUMS", write(staging, "SHA256SUMS", sums(files)));

            Path zip = out.resolve(base + ".zip");
            Zips.zip(files, time, zip);
            Path manifestCopy = out.resolve(base + ".manifest.json");
            Files.writeString(manifestCopy, manifestText);
            return new Result(zip, manifestCopy, warnings);
        } finally {
            try (Stream<Path> paths = Files.walk(staging)) {
                paths.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** {@code <sha256>  <path>} lines, sorted by path (packaging.md §7.1). */
    static String sums(Map<String, Path> files) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Path> file : new TreeMap<>(files).entrySet()) {
            text.append(sha256(Files.readAllBytes(file.getValue()))).append("  ").append(file.getKey()).append('\n');
        }
        return text.toString();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }
}
