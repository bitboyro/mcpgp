package ro.bitboy.mcpgp.packager;

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
    private static final List<String> TARGETS = List.of("jar", "mcpb", "docker");

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
        return build(manifest, definition, time, out, null);
    }

    /** @param signing the builder's key to sign {@code SHA256SUMS} with; null for an unsigned package */
    /** One way the package can be installed: {@code jar} (always), {@code mcpb}, {@code docker}; {@code file} is its main file. */
    public record Format(String id, String file) {
    }

    /**
     * What a build of this definition will contain, without building it: the formats, every file of the
     * package (in the order {@link #build} writes them), the generated docs in full, and the warnings.
     */
    public record Preview(String zip, List<Format> formats, List<String> files, Map<String, String> docs, List<String> warnings) {
    }

    public static Preview preview(ObjectNode manifest, JsonNode definition, boolean signed) throws IOException {
        String server = manifest.at("/server/name").asText();
        String base = server + "-" + manifest.at("/server/version").asText();
        List<String> warnings = new ArrayList<>();
        java.util.Set<String> targets = targets(definition, warnings);
        List<Format> formats = new ArrayList<>(List.of(new Format("jar", server + ".jar")));
        java.util.Set<String> names = new java.util.LinkedHashSet<>(List.of(server + ".jar"));
        if (targets.contains("mcpb")) {
            formats.add(new Format("mcpb", base + ".mcpb"));
            names.add(base + ".mcpb");
        }
        if (targets.contains("docker")) {
            java.util.Set<String> container = Container.files(manifest, definition, warnings).keySet();
            formats.add(new Format("docker", container.stream().filter(n -> n.contains("compose")).findFirst()
                    .orElse(container.iterator().next())));
            names.addAll(container);
        }
        names.addAll(List.of("manifest.json", "server.config.schema.json", server + ".example.yaml"));
        Map<String, String> docs = new Docs(manifest, ConfigSchema.generate(manifest), signed, names).generate(agentDocs(definition));
        names.addAll(docs.keySet());
        if (signed) {
            names.add("SIGNING-KEY.pub");
        } else {
            warnings.add("the package is not signed (set build.sign.key to sign SHA256SUMS)");
        }
        names.add("SHA256SUMS");
        if (signed) {
            names.add("SHA256SUMS.sig");
        }
        return new Preview(base + ".zip", List.copyOf(formats), List.copyOf(names), docs, List.copyOf(warnings));
    }

    /** {@code build.targets}, with a warning for each one this version can't build, and for a generation mode it can't use. */
    private static java.util.Set<String> targets(JsonNode definition, List<String> warnings) {
        for (JsonNode target : definition.at("/build/targets")) {
            if (!TARGETS.contains(target.asText())) {
                warnings.add("build target " + target.asText() + " is not available in this version; the package has the jar only");
            }
        }
        java.util.Set<String> targets = new java.util.LinkedHashSet<>();
        definition.at("/build/targets").forEach(t -> targets.add(t.asText()));
        String mode = definition.at("/generation/mode").asText("runtime");
        if (!mode.equals("runtime")) {
            warnings.add("generation.mode " + mode + " is not available in this version; the server works from its manifest");
        }
        return targets;
    }

    private static boolean agentDocs(JsonNode definition) {
        for (JsonNode audience : definition.at("/build/docs")) {
            if (audience.asText().equals("agents")) {
                return true;
            }
        }
        return false;
    }

    /** The bytes of an extension artifact, by its path in the definition project (extensions.md §1). */
    @FunctionalInterface
    public interface Artifacts {
        byte[] read(String path) throws IOException;

        /** Artifacts read from the definition project's folder. */
        static Artifacts in(Path root) {
            return path -> Files.readAllBytes(root.resolve(path).normalize());
        }

        Artifacts NONE = path -> {
            throw new IOException("extension artifact " + path + " is not available to this build");
        };
    }

    /** Where the server jar carries extension artifacts (the runtime loads them from there). */
    public static final String ARTIFACTS_FOLDER = "BOOT-INF/classes/mcpgp/artifacts/";

    public Result build(ObjectNode manifest, JsonNode definition, Instant time, Path out, Minisign.Keys signing) throws IOException {
        return build(manifest, definition, Artifacts.NONE, time, out, signing);
    }

    /** @param artifacts the definition's extension artifacts (checked against the manifest's checksums) */
    public Result build(ObjectNode manifest, JsonNode definition, Artifacts artifacts, Instant time, Path out, Minisign.Keys signing) throws IOException {
        String server = manifest.at("/server/name").asText();
        String version = manifest.at("/server/version").asText();
        String base = server + "-" + version;
        List<String> warnings = new ArrayList<>();
        java.util.Set<String> targets = targets(definition, warnings);

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
            Map<String, byte[]> embedded = new LinkedHashMap<>();
            embedded.put(MANIFEST_ENTRY, manifestText.getBytes(StandardCharsets.UTF_8));
            for (JsonNode artifact : manifest.at("/extensions/artifacts")) {
                String path = artifact.path("path").asText();
                byte[] bytes = artifacts.read(path);
                if (!("sha256:" + sha256(bytes)).equals(artifact.path("sha256").asText())) {
                    throw new IOException("extension artifact " + path + " changed since it was compiled");
                }
                embedded.put(ARTIFACTS_FOLDER + path, bytes);
            }
            Zips.copyWith(runtimeJar, embedded, time, jar);
            Files.delete(runtimeJar);
            files.put(server + ".jar", jar);

            if (targets.contains("mcpb")) {
                Path bundleManifest = write(staging, ".mcpb/manifest.json", CanonicalJson.pretty(Bundle.manifest(manifest)));
                Path bundle = staging.resolve(base + ".mcpb");
                Zips.zip(Map.of("manifest.json", bundleManifest, "server/" + server + ".jar", jar), time, bundle);
                files.put(base + ".mcpb", bundle);
            }
            if (targets.contains("docker")) {
                for (Map.Entry<String, String> file : Container.files(manifest, definition, warnings).entrySet()) {
                    files.put(file.getKey(), write(staging, file.getKey(), file.getValue()));
                }
            }

            files.put("manifest.json", write(staging, "manifest.json", manifestText));
            ObjectNode configSchema = ConfigSchema.generate(manifest);
            files.put("server.config.schema.json", write(staging, "server.config.schema.json", CanonicalJson.pretty(configSchema)));
            files.put(server + ".example.yaml", write(staging, server + ".example.yaml", ExampleConfig.yaml(manifest)));
            for (Map.Entry<String, String> doc : new Docs(manifest, configSchema, signing != null, files.keySet()).generate(agentDocs(definition)).entrySet()) {
                files.put(doc.getKey(), write(staging, doc.getKey(), doc.getValue()));
            }
            if (signing != null) {
                files.put("SIGNING-KEY.pub", write(staging, "SIGNING-KEY.pub", Minisign.publicKeyFile(signing)));
            } else {
                warnings.add("the package is not signed (set build.sign.key to sign SHA256SUMS)");
            }

            String sums = sums(files);
            files.put("SHA256SUMS", write(staging, "SHA256SUMS", sums));
            if (signing != null) {
                try {
                    String comment = "timestamp:" + time.getEpochSecond() + "\tfile:SHA256SUMS\thashed\tserver:" + base;
                    files.put("SHA256SUMS.sig", write(staging, "SHA256SUMS.sig",
                            Minisign.sign(signing, sums.getBytes(StandardCharsets.UTF_8), comment)));
                } catch (java.security.GeneralSecurityException e) {
                    throw new IOException("signing failed: " + e.getMessage());
                }
            }

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
