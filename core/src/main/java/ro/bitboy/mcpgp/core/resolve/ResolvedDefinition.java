package ro.bitboy.mcpgp.core.resolve;

import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.core.diag.Location;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A definition with every file resolved: {@code $ref}s inlined, rule files collected, the naming policy
 * and the parent (for variants) merged in, and contract paths turned into absolute files.
 *
 * @param file       the root definition file
 * @param root       the project root (the root file's directory)
 * @param document   the merged definition; {@code api} is normalized to {@code apis}; {@code extends},
 *                   {@code overrides}, {@code naming.extends} and {@code descriptions.include} are removed
 *                   (included notices are in {@code descriptions.notices})
 * @param rules      every rule in application order, with where it came from
 * @param contracts  each contract's spec file and overlay files
 * @param parent     the parent's name, for variants; null otherwise
 * @param files      every file read, for digests
 * @param noticeSources for notices that came from {@code descriptions.include}: notice id → the reference
 */
public record ResolvedDefinition(
        Path file,
        Path root,
        ObjectNode document,
        List<SourcedRule> rules,
        Map<ContractKey, ContractFiles> contracts,
        String parent,
        List<Path> files,
        Map<String, String> noticeSources) {

    public ResolvedDefinition(Path file, Path root, ObjectNode document, List<SourcedRule> rules, Map<ContractKey, ContractFiles> contracts,
                              String parent, List<Path> files) {
        this(file, root, document, rules, contracts, parent, files, Map.of());
    }

    /** A rule and where it was declared. */
    public record SourcedRule(ObjectNode rule, Location location) {
    }

    /** Identifies a contract: API id and contract version. */
    public record ContractKey(String api, String version) {
        @Override
        public String toString() {
            return api + "@" + version;
        }
    }

    /** The files of one contract. */
    public record ContractFiles(Path spec, List<Path> overlays) {
    }
}
