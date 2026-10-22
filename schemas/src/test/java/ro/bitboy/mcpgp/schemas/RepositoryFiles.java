package ro.bitboy.mcpgp.schemas;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Decides which schema applies to a file under {@code examples/} or {@code conformance/}.
 *
 * <p>Every file must be either classified or explicitly skipped, so a new example can't silently
 * escape validation.
 */
final class RepositoryFiles {

    enum Decision { VALIDATE, SKIP, UNKNOWN }

    record Classification(Decision decision, Optional<SchemaKind> kind, String reason) {
        static Classification validate(SchemaKind kind) {
            return new Classification(Decision.VALIDATE, Optional.of(kind), kind.name());
        }

        static Classification skip(String reason) {
            return new Classification(Decision.SKIP, Optional.empty(), reason);
        }

        static Classification unknown() {
            return new Classification(Decision.UNKNOWN, Optional.empty(), "no rule");
        }
    }

    private RepositoryFiles() {
    }

    /** {@code relative} is relative to the repository root and uses '/' separators. */
    static Classification classify(String relative) {
        String name = relative.substring(relative.lastIndexOf('/') + 1);

        if (relative.startsWith("conformance/runner/")) {
            return Classification.skip("a conformance runner's code (and, locally, its dependencies)");
        }
        if (relative.startsWith("conformance/fixtures/golden/")) {
            return Classification.skip("a golden surface, checked by the release gate (S2)");
        }
        if (name.endsWith(".md") || name.equals(".DS_Store")) {
            return Classification.skip("documentation");
        }
        if (name.endsWith(".mcpgp.yaml")) {
            return Classification.validate(SchemaKind.DEFINITION);
        }
        if (name.equals("mcp-names.lock")) {
            return Classification.validate(SchemaKind.NAMES_LOCK);
        }
        if (name.equals("mcpgp-workspace.yaml")) {
            return Classification.validate(SchemaKind.WORKSPACE);
        }
        if (relative.contains("/overrides/") && name.endsWith(".yaml")) {
            return Classification.validate(SchemaKind.RULES);
        }
        if (relative.contains("/workspace/naming/") && name.endsWith(".yaml")) {
            return Classification.validate(SchemaKind.NAMING_POLICY);
        }
        if (relative.contains("/workspace/notices/") && name.endsWith(".yaml")) {
            return Classification.validate(SchemaKind.NOTICES);
        }
        if (relative.endsWith("/.mcpgp/config.yaml")) {
            return Classification.validate(SchemaKind.CLI_CONFIG);
        }
        if (relative.contains("/tests/") && name.endsWith(".yaml")) {
            return Classification.validate(SchemaKind.TOOL_TESTS);
        }
        if (relative.startsWith("examples/manifest/") && name.endsWith(".json")) {
            return Classification.validate(SchemaKind.MANIFEST);
        }
        if (relative.startsWith("examples/audit/") && name.endsWith(".jsonl")) {
            return Classification.validate(SchemaKind.AUDIT_EVENT);
        }

        if (relative.startsWith("conformance/")) {
            if (name.equals("case.yaml")) {
                return Classification.validate(SchemaKind.CONFORMANCE_CASE);
            }
            if (name.equals("traffic.yaml")) {
                return Classification.validate(SchemaKind.CONFORMANCE_TRAFFIC);
            }
            if (name.equals("runtime.yaml")) {
                return Classification.validate(SchemaKind.RUNTIME_CONFIG);
            }
            if (relative.endsWith("/expected/manifest.json")) {
                return Classification.validate(SchemaKind.MANIFEST);
            }
            if (relative.endsWith("/expected/diagnostics.json")) {
                return Classification.validate(SchemaKind.CONFORMANCE_DIAGNOSTICS);
            }
            if (relative.contains("/contracts/") || name.equals("mock.yaml")) {
                return Classification.skip("OpenAPI contract or mock data");
            }
        }

        if (relative.startsWith("examples/client/")) {
            if (name.equals("docker-compose.yml")) {
                return Classification.skip("Docker Compose file");
            }
            if (name.endsWith(".env.example") || name.equals(".env.example")) {
                return Classification.skip("environment file");
            }
            if (relative.startsWith("examples/client/mcp-clients/")) {
                return Classification.skip("MCP client configuration (client-owned format)");
            }
            if (name.endsWith(".yaml") || name.endsWith(".json")) {
                return Classification.validate(SchemaKind.RUNTIME_CONFIG);
            }
        }

        if (relative.contains("/contracts/")) {
            return Classification.skip("OpenAPI contract");
        }
        if (relative.contains("/overlays/")) {
            return Classification.skip("OpenAPI Overlay document");
        }
        if (relative.contains("/packs/")) {
            return Classification.skip("pack configuration (validated by the pack)");
        }
        if (relative.startsWith("examples/pipeline/.github/") || name.equals(".gitlab-ci.yml")) {
            return Classification.skip("CI configuration");
        }
        return Classification.unknown();
    }

    static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
