package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code GET} operations exposed as MCP resources (tools-contract §14.1). Each resource of the manifest
 * becomes, per configured instance in multi-instance builds, a resource ({@code api://status}) or a
 * resource template ({@code api://prod-eu/customers/{id}}). A read becomes the operation's arguments,
 * and the call goes the way of any tool call (validation, sign-in, audit, redaction).
 */
final class Resources {

    private static final Logger LOG = LoggerFactory.getLogger(Resources.class);

    /** One readable URI pattern: the resource it reads, and the instance its URIs name (multi-instance). */
    record Entry(JsonNode resource, String instance, String uri, UriTemplate template) {
        boolean isTemplate() {
            return uri.contains("{");
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    Resources(Manifest manifest, RuntimeConfig config) {
        JsonNode section = manifest.root().path("resources");
        String scheme = section.path("uriScheme").asText("api");
        for (JsonNode item : section.path("items")) {
            // as for tools (ToolCatalog): the instance parameter offers the configured instances of its API
            com.fasterxml.jackson.databind.node.ObjectNode resource = item.deepCopy();
            JsonNode instanceParam = resource.at("/inputSchema/properties/instance");
            if (instanceParam instanceof com.fasterxml.jackson.databind.node.ObjectNode property && property.path("x-mcpgp-instanceEnum").asBoolean(false)) {
                var ids = property.putArray("enum");
                config.instances().getOrDefault(resource.path("api").asText(), List.of()).forEach(i -> ids.add(i.id()));
                property.remove("x-mcpgp-instanceEnum");
            }
            String template = resource.path("uriTemplate").asText();
            if (manifest.multiInstance()) {
                for (RuntimeConfig.Instance instance : config.instances().getOrDefault(resource.path("api").asText(), List.of())) {
                    String uri = scheme + "://" + instance.id() + "/" + template;
                    entries.add(new Entry(resource, instance.id(), uri, new UriTemplate(uri)));
                }
            } else {
                String uri = scheme + "://" + template;
                entries.add(new Entry(resource, null, uri, new UriTemplate(uri)));
            }
        }
    }

    List<Entry> entries() {
        return entries;
    }

    boolean any() {
        return !entries.isEmpty();
    }

    McpSchema.Resource resource(Entry entry) {
        JsonNode r = entry.resource();
        return McpSchema.Resource.builder().uri(entry.uri()).name(r.path("name").asText()).title(r.path("title").asText(null))
                .description(r.path("description").asText(null)).mimeType(r.path("mimeType").asText(null)).build();
    }

    McpSchema.ResourceTemplate template(Entry entry) {
        JsonNode r = entry.resource();
        return new McpSchema.ResourceTemplate(entry.uri(), r.path("name").asText(), r.path("title").asText(null),
                r.path("description").asText(null), r.path("mimeType").asText(null), null, null);
    }

    /** The entry a URI reads, and the operation's arguments for it. */
    Optional<Map.Entry<Entry, Map<String, Object>>> resolve(String uri) {
        for (Entry entry : entries) {
            if (!entry.template().matches(uri)) {
                continue;
            }
            List<String> dropped = entry.template().undeclared(uri);
            if (!dropped.isEmpty()) {
                LOG.info("Query parameters not declared by {}: {} (dropped)", entry.resource().path("name").asText(), dropped);
            }
            Map<String, String> values = entry.template().extractVariableValues(uri);
            Map<String, Object> arguments = new LinkedHashMap<>();
            if (entry.instance() != null) {
                arguments.put("instance", entry.instance());
            }
            JsonNode parameters = parameters(entry.resource());
            Map<String, Object> path = new LinkedHashMap<>();
            Map<String, Object> query = new LinkedHashMap<>();
            values.forEach((name, value) -> {
                Object typed = typed(value, schema(parameters, name));
                if (entry.template().pathNames().contains(name)) {
                    path.put(name, typed);
                } else {
                    query.put(name, typed);
                }
            });
            if (!path.isEmpty()) {
                arguments.put("path", path);
            }
            if (!query.isEmpty()) {
                arguments.put("query", query);
            }
            return Optional.of(Map.entry(entry, arguments));
        }
        return Optional.empty();
    }

    /** The parameters of the operation in its default contract (the one its URI template comes from). */
    private static JsonNode parameters(JsonNode resource) {
        var variants = resource.path("variants").elements();
        return variants.hasNext() ? variants.next().path("parameters") : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static JsonNode schema(JsonNode parameters, String name) {
        for (JsonNode p : parameters) {
            if (p.path("name").asText().equals(name)) {
                return p.path("schema");
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    /** URI values are text; the operation may want a number or a boolean. */
    private static Object typed(String value, JsonNode schema) {
        String type = schema.path("type").asText(schema.path("type").isArray() ? schema.path("type").get(0).asText() : "string");
        try {
            return switch (type) {
                case "integer" -> Long.parseLong(value);
                case "number" -> Double.parseDouble(value);
                case "boolean" -> value.equalsIgnoreCase("true") ? Boolean.TRUE : value.equalsIgnoreCase("false") ? Boolean.FALSE : value;
                default -> value;
            };
        } catch (NumberFormatException e) {
            return value;          // validation explains what is wrong
        }
    }

    /** What a resource read returns: the tool output as text, or the file as a blob. */
    static McpSchema.ReadResourceResult contents(String uri, JsonNode resource, Object output) {
        String mimeType = resource.path("mimeType").asText("application/json");
        if (output instanceof ToolOutput tool) {
            if (tool.text() != null) {
                return new McpSchema.ReadResourceResult(List.of(new McpSchema.TextResourceContents(uri, mimeType, tool.text())));
            }
            if (tool.structured() instanceof Map<?, ?> file && file.get("base64") != null) {
                return new McpSchema.ReadResourceResult(List.of(new McpSchema.BlobResourceContents(uri,
                        String.valueOf(file.get("contentType")), String.valueOf(file.get("base64")))));
            }
            return new McpSchema.ReadResourceResult(List.of(new McpSchema.TextResourceContents(uri, "application/json",
                    Results.json(tool.answer()))));
        }
        return new McpSchema.ReadResourceResult(List.of(new McpSchema.TextResourceContents(uri, mimeType, Results.json(output))));
    }
}
