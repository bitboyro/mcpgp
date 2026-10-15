package ro.bitboy.mcpgp.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import ro.bitboy.mcpgp.schemas.SchemaKind;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code .mcpgp/config.yaml} (spec/cli.md §2): the CLI version a repository is built with, its
 * servers (for {@code --all}), the workspace, the output folder and the signing key. Paths are
 * relative to the repository root, the folder that holds {@code .mcpgp/}.
 *
 * @param file the config file
 * @param root the repository root
 */
/** @param baseline what {@code diff} compares against when {@code --against} isn't given ({@code release.baseline}) */
record RepoConfig(Path file, Path root, String cli, List<Path> servers, Path workspace, Path out, String signKey, String baseline, List<String> notYet) {

    static final String FILE = ".mcpgp/config.yaml";

    /** The config named by {@code --config}, else the nearest {@code .mcpgp/config.yaml} from {@code dir} upwards; null when there is none. */
    static RepoConfig find(Path explicit, Path dir) throws IOException {
        Path file = explicit;
        if (file == null) {
            for (Path d = dir.toAbsolutePath().normalize(); d != null; d = d.getParent()) {
                if (Files.isRegularFile(d.resolve(FILE))) {
                    file = d.resolve(FILE);
                    break;
                }
            }
        }
        if (file == null) {
            return null;
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(file + " doesn't exist");
        }
        return load(file.toAbsolutePath().normalize());
    }

    static RepoConfig load(Path file) throws IOException {
        JsonNode doc = new YAMLMapper().readTree(file.toFile());
        if (doc == null || !doc.isObject()) {
            throw new IllegalArgumentException(file + ": not a YAML mapping");
        }
        List<String> problems = new Schemas().validate(SchemaKind.CLI_CONFIG, doc);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(file + ": " + String.join("; ", problems));
        }
        // .mcpgp/config.yaml → the repository root; a config given with --config elsewhere → its own folder
        Path parent = file.getParent();
        Path root = parent.getFileName() != null && parent.getFileName().toString().equals(".mcpgp") ? parent.getParent() : parent;
        List<Path> servers = new ArrayList<>();
        doc.path("servers").forEach(s -> servers.add(root.resolve(s.asText()).normalize()));
        List<String> notYet = new ArrayList<>();
        String versionFrom = doc.at("/release/versionFrom").asText("contract");
        if (!versionFrom.equals("contract")) {
            notYet.add("release.versionFrom: " + versionFrom + " is not supported yet; the version follows the contract (or --version)");
        }
        if (doc.has("platform")) {
            notYet.add("platform: workspace references from a platform are not supported yet; use workspace");
        }
        return new RepoConfig(file, root, doc.path("cli").asText(), List.copyOf(servers),
                doc.hasNonNull("workspace") ? root.resolve(doc.path("workspace").asText()).normalize() : null,
                doc.hasNonNull("out") ? root.resolve(doc.path("out").asText()).normalize() : null,
                doc.at("/sign/key").asText(null), doc.at("/release/baseline").asText("last-release"), List.copyOf(notYet));
    }
}
