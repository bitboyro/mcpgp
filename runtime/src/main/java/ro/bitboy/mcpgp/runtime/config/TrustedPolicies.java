package ro.bitboy.mcpgp.runtime.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import ro.bitboy.mcpgp.schemas.SchemaKind;
import ro.bitboy.mcpgp.schemas.Schemas;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Trusted mounted policy files: required when selected; reload failures never drop restrictions. */
public final class TrustedPolicies {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private TrustedPolicies() { }
    public static ObjectNode load(Optional<Path> path, SchemaKind kind, Schemas schemas, List<String> problems) {
        if (path.isEmpty()) { return YAML.createObjectNode(); }
        try (var input = java.nio.file.Files.newInputStream(path.get())) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) { throw new java.io.IOException(); }
            JsonNode node = YAML.readTree(bytes);
            if (!(node instanceof ObjectNode object)) { throw new java.io.IOException(); }
            schemas.problems(kind, node).forEach(p -> problems.add(kind + ": " + p.pointer() + ": " + p.message()));
            return object;
        } catch (java.io.IOException e) {
            problems.add(kind + ": required administrator policy file is unavailable or invalid");
            return YAML.createObjectNode();
        }
    }
    public static void enforce(JsonNode policy, RuntimeConfig.Server server, java.util.Map<String, List<RuntimeConfig.Instance>> instances,
                               JsonNode document, JsonNode buildRates, List<String> problems) {
        JsonNode c = policy.path("constraints");
        if (c.path("requireAudit").asBoolean() && !document.at("/audit/file/enabled").asBoolean(true)) { problems.add("organization requires audit logging"); }
        if (document.at("/audit/file/retainDays").asInt(30) < c.path("minAuditRetainDays").asInt(1)) { problems.add("organization minimum audit retention exceeded"); }
        for (String key : List.of("allowedTransports", "allowedAuthModes")) {
            String value = key.equals("allowedTransports") ? server.transport() : server.authMode();
            if (c.has(key) && java.util.stream.StreamSupport.stream(c.path(key).spliterator(), false).noneMatch(v -> v.asText().equals(value))) { problems.add("organization rejects " + key); }
        }
        instances.values().stream().flatMap(List::stream).forEach(i -> {
            if (c.path("requireReadOnly").asBoolean() && !i.readOnly()) { problems.add("organization requires readOnly on " + i.api() + "/" + i.id()); }
            if (i.timeoutSeconds() > c.path("maxTimeoutSeconds").asInt(Integer.MAX_VALUE)) { problems.add("organization maxTimeoutSeconds exceeded on " + i.id()); }
            for (String key : List.of("concurrent", "perMinute")) {
                String bound = key.equals("concurrent") ? "maxConcurrent" : "maxPerMinute";
                int defaultValue = buildRates.path(key).asInt(key.equals("concurrent") ? 8 : 120);
                if (c.has(bound) && (!i.rateLimits().path("enabled").asBoolean(buildRates.path("enabled").asBoolean(true)) || i.rateLimits().path(key).asInt(defaultValue) > c.path(bound).asInt())) { problems.add("organization " + bound + " exceeded on " + i.id()); }
            }
        });
    }
}
