package ro.bitboy.mcpgp.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

/** Built-in, versioned configuration choices offered after importing a contract. */
public final class ConfigurationTemplates {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Store store;

    public ConfigurationTemplates(Store store) { this.store = store; }

    public record Template(String id, String title, String description, int version,
                           Map<String, Object> defaults, List<String> requiredInputs) {}

    public List<Template> builtIns() {
        return List.of(
                new Template("blank", "Bare", "Keep the generated project, with no tools enabled by policy.", 1,
                        Map.of("tools", Map.of("allow", List.of())), List.of()),
                new Template("default", "Default", "Use the generated contract configuration.", 1,
                        Map.of(), List.of()),
                new Template("personal-use", "Basic", "A local stdio configuration with read-only defaults.", 1,
                        Map.of("server", Map.of("transport", "stdio"), "tools", Map.of("readOnly", true)), List.of()),
                new Template("enterprise-grade", "Enterprise", "No API tools allowed until selected; audit and bounded calls. Configure authentication before shared deployment.", 1,
                        Map.of("tools", Map.of("allow", List.of()), "audit", Map.of("file", Map.of("enabled", true, "retainDays", 90)),
                                "settings", Map.of("rateLimits", Map.of("enabled", true, "concurrent", 4, "perMinute", 60), "timeoutSeconds", 15)),
                        List.of("tool selection", "authentication key")));
    }

    /** Built-ins plus the latest immutable version of each Library template. */
    public List<Template> all() {
        java.util.ArrayList<Template> result = new java.util.ArrayList<>(builtIns());
        for (Model.LibraryItem item : store.library()) {
            if (item.kind().equals("templates") && item.latest() != null
                    && !(LibraryExamples.example(item) && item.versions().size() == 1
                    && builtIns().stream().anyMatch(t -> LibraryExamples.templateName(t.id()).equals(item.name())))) result.add(get("library/" + item.name() + "@" + item.latest().number()));
        }
        return List.copyOf(result);
    }

    public Template get(String id) {
        var builtIn = builtIns().stream().filter(t -> t.id().equals(id)).findFirst();
        if (builtIn.isPresent()) return builtIn.get();
        if (!id.startsWith("library/")) throw new IllegalArgumentException("unknown configuration template: " + id);
        String[] bits = id.substring("library/".length()).split("@", 2);
        Model.LibraryItem item = store.libraryItem("templates", bits[0]).orElseThrow(() -> new IllegalArgumentException("unknown configuration template: " + id));
        Model.VersionRef ref = bits.length == 1 ? item.latest() : item.versions().stream().filter(v -> Integer.toString(v.number()).equals(bits[1])).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown configuration template: " + id));
        try {
            var doc = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(store.blob(store.version(ref.digest()).orElseThrow().files().get(store.version(ref.digest()).orElseThrow().root())));
            return new Template("library/" + item.name() + "@" + ref.number(), doc.path("title").asText(item.title()), doc.path("description").asText(""), ref.number(),
                    JSON.convertValue(doc.path("configuration"), Map.class), JSON.convertValue(doc.path("requiredInputs"), List.class));
        } catch (Exception e) { throw new IllegalArgumentException("invalid configuration template " + id, e); }
    }

    /** A stable token returned only by a preview; clients must send it back to materialize a choice. */
    public String acceptance(Template template) {
        return template.id().matches(".*@\\d+$") ? template.id() : template.id() + "@" + template.version();
    }

    /** Checks the compact, declarative Library template format before it can be versioned. */
    public static List<String> validate(String text) {
        try {
            var doc = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(text);
            if (doc == null || !doc.path("configuration").isObject()) return List.of("configuration: an object is required");
            if (!doc.path("requiredInputs").isMissingNode() && !doc.path("requiredInputs").isArray()) return List.of("requiredInputs: an array is required");
            var schemas = new ro.bitboy.mcpgp.schemas.Schemas();
            return schemas.problems(ro.bitboy.mcpgp.schemas.SchemaKind.RUNTIME_CONFIG, doc.path("configuration")).stream()
                    .map(p -> "configuration" + (p.pointer() == null ? "" : p.pointer()) + ": " + p.message()).toList();
        } catch (Exception e) { return List.of("not valid YAML: " + e.getMessage()); }
    }

    /** Applies only declared runtime defaults to a generated definition. */
    public byte[] apply(byte[] definition, String id) {
        Template template = get(id);
        if (template.defaults().isEmpty()) return definition;
        try {
            ObjectNode root = (ObjectNode) new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(definition);
            ObjectNode runtime = root.withObject("runtimeDefaults");
            ObjectNode configuration = runtime.withObject("configuration");
            merge(configuration, JSON.valueToTree(template.defaults()));
            return new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("generated definition cannot accept template defaults", e);
        }
    }

    private static void merge(ObjectNode target, com.fasterxml.jackson.databind.JsonNode source) {
        source.fields().forEachRemaining(entry -> {
            if (entry.getValue().isObject() && target.path(entry.getKey()).isObject())
                merge((ObjectNode) target.get(entry.getKey()), entry.getValue());
            else target.set(entry.getKey(), entry.getValue().deepCopy());
        });
    }
}
