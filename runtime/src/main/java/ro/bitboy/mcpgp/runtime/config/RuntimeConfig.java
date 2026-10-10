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
        List<String> warnings) {

    /**
     * @param authMode inbound auth: {@code none}, {@code static}, {@code oauth}
     */
    public record Server(String transport, String host, int port, String basePath, String publicUrl,
                         String authMode, JsonNode auth, boolean allowInsecure, List<String> allowedOrigins) {
    }

    /**
     * One target server.
     *
     * @param id       what agents pass as {@code instance}; {@code default} in single-instance builds
     * @param contract a shipped contract version, or {@code auto}
     * @param auth     the resolved {@code auth} object (secrets resolved); {@code {type: none}} when absent
     * @param tls      the resolved {@code tls} object; empty when the system trust store is used
     * @param tlsContext the instance's own TLS context; null when {@code tls} is empty
     * @param baseDir  where relative material paths start (to rebuild the context after rotation)
     */
    public record Instance(String api, String id, String baseUrl, String contract, boolean readOnly,
                           int timeoutSeconds, JsonNode auth, JsonNode tls, TlsBuilder.InstanceTls tlsContext, Path baseDir) {
        public String authType() {
            return auth.path("type").asText("none");
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
