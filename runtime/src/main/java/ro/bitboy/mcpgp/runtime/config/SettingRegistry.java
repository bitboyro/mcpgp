package ro.bitboy.mcpgp.runtime.config;

import java.util.List;
import java.util.Map;

/** Nonsensitive editable settings, their locations and live/restart contract. */
public final class SettingRegistry {
    public record Field(String path, boolean live, String type) { }
    private static final List<Field> FIELDS = List.of(
            new Field("/logging/level", true, "string"), new Field("/errors/detail", true, "string"),
            new Field("/audit/file/retainDays", true, "integer"), new Field("/audit/file/enabled", true, "boolean"),
            new Field("/server/transport", false, "string"), new Field("/server/host", false, "string"),
            new Field("/server/port", false, "integer"), new Field("/server/basePath", false, "string"),
            new Field("/server/auth/mode", false, "string"), new Field("/storage/root", false, "string"));
    private SettingRegistry() { }
    public static boolean policy(String path) {
        return path.matches("^/(?:apis/[^/]+/)?(?:tools|instance/tools|instances/(?:0|[1-9][0-9]*)/tools)$");
    }
    public static boolean editable(String path) {
        return policy(path) || FIELDS.stream().anyMatch(f -> f.path().equals(path))
                || path.matches("^/(?:apis/[^/]+/)?(?:settings|instance|instances/(?:0|[1-9][0-9]*))/(?:timeoutSeconds|readOnly|rateLimits(?:/(?:enabled|concurrent|perMinute))?)$");
    }
    public static List<Map<String, Object>> describe(RuntimeConfig config) {
        var result = new java.util.ArrayList<Map<String, Object>>();
        var values = new java.util.LinkedHashMap<String, Object>();
        values.put("/logging/level", config.logLevel()); values.put("/errors/detail", config.errorDetail());
        values.put("/audit/file/enabled", config.audit().enabled()); values.put("/audit/file/retainDays", config.audit().retainDays());
        values.put("/server/transport", config.server().transport()); values.put("/server/host", config.server().host()); values.put("/server/port", config.server().port());
        values.put("/server/basePath", config.server().basePath()); values.put("/server/auth/mode", config.server().authMode()); values.put("/storage/root", config.storageRoot().toString());
        values.forEach((path, value) -> result.add(Map.of("path", path, "value", value, "source", source(config, path))));
        config.instances().forEach((api, targets) -> {
            for (int index = 0; index < targets.size(); index++) {
                var target = targets.get(index);
                String scope = config.instances().size() > 1 ? "/apis/" + api : "";
                String at = scope + (config.document().at(scope + "/instances").isArray() ? "/instances/" + index : "/instance");
                for (String field : List.of("timeoutSeconds", "readOnly", "rateLimits")) {
                    Object value = field.equals("timeoutSeconds") ? target.timeoutSeconds() : field.equals("readOnly") ? target.readOnly() : target.rateLimits();
                    String origin = config.document().at(at + "/" + field).isMissingNode() ? scope + "/settings/" + field : at + "/" + field;
                    if (config.document().at(origin).isMissingNode()) { origin = "/settings/" + field; }
                    result.add(Map.of("path", at + "/" + field, "value", value, "source", source(config, origin)));
                }
            }
        });
        return List.copyOf(result);
    }
    private static String source(RuntimeConfig config, String path) {
        for (String kind : List.of("_flags", "_environment")) { for (var value : config.document().path(kind)) { if (path.startsWith(value.asText()) || value.asText().startsWith(path)) { return kind.equals("_flags") ? "CLI" : "environment"; } } }
        if (!config.document().path("_organization").path("defaults").at(path).isMissingNode()) { return "organization default or deployment override: " + path; }
        return config.document().at(path).isMissingNode() ? "build/default" : "deployment: " + path;
    }

    public static List<Map<String, Object>> fields() {
        var fields = new java.util.ArrayList<Map<String, Object>>();
        FIELDS.forEach(f -> fields.add(Map.of("path", f.path(), "live", f.live(), "type", f.type())));
        fields.add(Map.of("path", "/[apis/<api>/]settings|instance|instances/<index>/{timeoutSeconds,readOnly,rateLimits}", "live", true, "type", "inherited"));
        return List.copyOf(fields);
    }
}
