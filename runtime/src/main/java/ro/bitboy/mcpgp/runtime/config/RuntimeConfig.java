package ro.bitboy.mcpgp.runtime.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The validated, resolved runtime config (runtime-config.md): secrets resolved, defaults applied and
 * checked against what the build allows.
 *
 * @param source        the config file, if one was used
 * @param instances     instances per API id, in config order
 * @param secrets       every secret value, for redaction
 * @param inlineSecrets started with {@code allowInlineSecrets} and plain secret values
 * @param warnings      things to log at startup
 * @param configHash    SHA-256 of the config as written (references, not secret values), for audit events
 */
public record RuntimeConfig(
        Optional<Path> source,
        JsonNode document,
        Server server,
        Map<String, List<Instance>> instances,
        Path storageRoot,
        Audit audit,
        Files files,
        String errorDetail,
        String logLevel,
        JsonNode telemetry,
        JsonNode packs,
        Set<String> secrets,
        boolean inlineSecrets,
        List<String> warnings,
        String configHash) {

    /**
     * @param authMode inbound auth: {@code none}, {@code static}, {@code oauth}
     * @param pageSize the list operations (tools, resources, templates, prompts) answer this many items per page;
     *                 0 = everything at once
     */
    public record Server(String transport, String host, int port, String basePath, String publicUrl,
                         String authMode, JsonNode auth, boolean allowInsecure, List<String> allowedOrigins,
                         Integer managementPort, int pageSize) {
    }

    /**
     * One target server.
     *
     * @param id       what agents pass as {@code instance}; {@code default} in single-instance builds
     * @param contract a shipped contract version, or {@code auto}
     * @param prefer   with {@code auto}: the contract detection asks about first; null for the build's default
     * @param auth     the resolved {@code auth}: one credential or a list tried in order (secrets resolved,
     *                 a fromRequest entry's header filled in); {@code {type: none}} when absent
     * @param tls      the resolved {@code tls} object; empty when the system trust store is used
     * @param tlsContext the instance's own TLS context; null when {@code tls} is empty
     * @param baseDir  where relative material paths start (to rebuild the context after rotation)
     * @param rateLimits the operator's changes to the build's rate limits for this instance ({@code enabled},
     *                 {@code concurrent}, {@code perMinute}); empty when there are none
     */
    public record Instance(String api, String id, String baseUrl, String contract, String prefer, boolean readOnly,
                           int timeoutSeconds, JsonNode auth, JsonNode tls, TlsBuilder.InstanceTls tlsContext, Path baseDir,
                           JsonNode rateLimits) {
        public Instance(String api, String id, String baseUrl, String contract, String prefer, boolean readOnly,
                        int timeoutSeconds, JsonNode auth, JsonNode tls, TlsBuilder.InstanceTls tlsContext, Path baseDir) {
            this(api, id, baseUrl, contract, prefer, readOnly, timeoutSeconds, auth, tls, tlsContext, baseDir,
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        }

        /** The credentials, in the order they're tried. */
        public List<JsonNode> authEntries() {
            if (!auth.isArray()) {
                return List.of(auth);
            }
            List<JsonNode> entries = new java.util.ArrayList<>();
            auth.forEach(entries::add);
            return entries;
        }

        /** The credential types, joined with {@code +} when there are several ({@code fromRequest+oauth2ClientCredentials}). */
        public String authType() {
            return String.join("+", authEntries().stream().map(e -> e.path("type").asText("none")).toList());
        }
    }

    public record Audit(boolean enabled, Path file, String rotate, int retainDays, String arguments) {
    }

    public record Files(boolean base64Enabled, int base64MaxKb, boolean pathEnabled, Path pathRoot, int pathMaxKb) {
    }

    /** All instances of every API, in order. */
    public List<Instance> allInstances() {
        return instances.values().stream().flatMap(List::stream).toList();
    }
}
