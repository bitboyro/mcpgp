package ro.bitboy.mcpgp.core.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Contract;
import ro.bitboy.mcpgp.core.openapi.ApiModel.MediaType;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Operation;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Parameter;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Response;
import ro.bitboy.mcpgp.core.openapi.ApiModel.SecurityRequirement;
import ro.bitboy.mcpgp.core.openapi.ApiModel.SecurityScheme;
import ro.bitboy.mcpgp.core.schema.SchemaShaper.Direction;
import ro.bitboy.mcpgp.core.tools.ToolPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds a tool's public input schema (the grouped shape, tools-contract §2–§4, §9, §13.4) and its
 * execution variants (manifest.md §5) from its plan and the contracts it exists in.
 */
public final class ToolShaper {

    /** The shaped tool: its served input schema and one variant per contract version, in contract order. */
    public record Shaped(ObjectNode inputSchema, ObjectNode variants) {
    }

    /**
     * How the tool is served.
     *
     * @param multiInstance        adds the required {@code instance} parameter
     * @param instanceDescription  the {@code instance} parameter's description
     * @param fileForms            file object forms the build supports ({@code base64}, {@code path})
     */
    public record Context(boolean multiInstance, String instanceDescription, List<String> fileForms) {
    }

    static final List<String> GROUPS = List.of("path", "query", "header", "cookie");
    private static final Map<String, String> GROUP_DESCRIPTIONS = Map.of(
            "path", "Values placed in the URL path.",
            "query", "Values sent in the URL query string.",
            "header", "Values sent as HTTP headers.",
            "cookie", "Values sent as cookies.");
    /** Headers the runtime sets itself; OpenAPI says their parameter definitions are ignored. */
    private static final Set<String> RESERVED_HEADERS = Set.of("authorization", "content-type", "accept");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Context context;

    public ToolShaper(Context context) {
        this.context = context;
    }

    /**
     * @param contracts the API's contracts by version, in definition order
     * @param defaultVersion the API's default contract version
     */
    public Shaped shape(ToolPlan plan, Map<String, Contract> contracts, String defaultVersion) {
        // variants in contract order; those that don't accept the tool's media type are left out
        Map<String, Operation> variants = new LinkedHashMap<>();
        contracts.forEach((version, contract) -> {
            Operation op = plan.variants().get(version);
            if (op != null && (plan.mediaType() == null || mediaType(op, plan.mediaType()) != null)) {
                variants.put(version, op);
            }
        });
        String primary = variants.containsKey(defaultVersion) ? defaultVersion : last(variants.keySet());
        List<String> order = new ArrayList<>();
        order.add(primary);
        variants.keySet().stream().filter(v -> !v.equals(primary)).forEach(order::add);

        Map<String, JsonNode> defs = new TreeMap<>();
        ObjectNode properties = NODES.objectNode();
        ArrayNode required = NODES.arrayNode();
        if (context.multiInstance()) {
            properties.set("instance", NODES.objectNode()
                    .put("type", "string")
                    .put("description", context.instanceDescription())
                    .<ObjectNode>set("enum", NODES.arrayNode())
                    .put("x-mcpgp-instanceEnum", true));
            required.add("instance");
        }

        // parameters: grouped by location; each one shown once, from the first variant that has it
        for (String group : GROUPS) {
            Map<String, Parameter> chosen = new LinkedHashMap<>();
            Map<String, String> chosenVersion = new LinkedHashMap<>();
            Map<String, List<String>> presentIn = new LinkedHashMap<>();
            for (String version : order) {
                Contract contract = contracts.get(version);
                for (Parameter p : visible(variants.get(version), contract, group)) {
                    if (chosen.putIfAbsent(p.name(), p) == null) {
                        chosenVersion.put(p.name(), version);
                    }
                    presentIn.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(version);
                }
            }
            if (chosen.isEmpty()) {
                continue;
            }
            ObjectNode members = NODES.objectNode();
            ArrayNode memberRequired = NODES.arrayNode();
            chosen.forEach((name, p) -> {
                Contract contract = contracts.get(chosenVersion.get(name));
                ObjectNode schema = shaper(contract).inline(p.schema(), Direction.INPUT, defs);
                String description = plan.paramDescriptions().getOrDefault(group + "." + name,
                        plan.paramDescriptions().getOrDefault(name, p.description()));
                List<String> versions = contractOrder(presentIn.get(name), variants.keySet());
                if (versions.size() < variants.size()) {
                    String only = "(" + String.join(", ", versions) + " only)";
                    description = description == null || description.isBlank() ? only : description + " " + only;
                    ArrayNode marks = NODES.arrayNode();
                    versions.forEach(marks::add);
                    schema.set("x-mcpgp-contracts", marks);
                }
                if (description != null && !description.isBlank()) {
                    schema.put("description", description);
                }
                if (p.deprecated()) {
                    schema.put("deprecated", true);
                }
                members.set(name, schema);
                if (p.required() || group.equals("path")) {
                    memberRequired.add(name);
                }
            });
            ObjectNode groupSchema = NODES.objectNode()
                    .put("type", "object")
                    .put("description", GROUP_DESCRIPTIONS.get(group));
            groupSchema.set("properties", members);
            if (!memberRequired.isEmpty()) {
                groupSchema.set("required", memberRequired);
                required.add(group);
            }
            groupSchema.put("additionalProperties", false);
            properties.set(group, groupSchema);
        }

        // body: from the primary variant
        Operation primaryOp = variants.get(primary);
        if (plan.mediaType() != null && primaryOp != null) {
            MediaType media = mediaType(primaryOp, plan.mediaType());
            ObjectNode body = inputBody(plan, media, shaper(contracts.get(primary)), defs);
            properties.set("body", body);
            if (primaryOp.requestBody().required()) {
                required.add("body");
            }
        }

        ObjectNode input = NODES.objectNode().put("type", "object");
        input.set("properties", properties);
        if (!required.isEmpty()) {
            input.set("required", required);
        }
        input.put("additionalProperties", false);
        if (!defs.isEmpty()) {
            ObjectNode defsNode = NODES.objectNode();
            defs.forEach(defsNode::set);
            input.set("$defs", defsNode);
        }

        ObjectNode variantNodes = NODES.objectNode();
        variants.forEach((version, op) -> variantNodes.set(version, variant(plan, op, contracts.get(version))));
        return new Shaped(input, variantNodes);
    }

    // ---------------------------------------------------------------------- input body

    private ObjectNode inputBody(ToolPlan plan, MediaType media, SchemaShaper shaper, Map<String, JsonNode> defs) {
        String type = media.name();
        String description = "Request body, sent as " + type + ".";
        if (isMultipart(type) || isForm(type)) {
            JsonNode schema = shaper.dereference(media.schema());
            ObjectNode converted = shaper.inline(schema, Direction.INPUT, defs);
            ObjectNode body = NODES.objectNode().put("type", "object").put("description", description);
            ObjectNode members = NODES.objectNode();
            converted.path("properties").fields().forEachRemaining(field -> {
                String name = field.getKey();
                ObjectNode override = plan.parts().get(name);
                JsonNode original = shaper.dereference(schema.path("properties").path(name));
                ObjectNode member;
                if (isMultipart(type) && isBinary(original)) {
                    member = fileObject(partTypes(name, original, media, override));
                } else if (isMultipart(type) && isBinaryArray(original)) {
                    member = NODES.objectNode().put("type", "array");
                    member.set("items", fileObject(partTypes(name, original.path("items"), media, override)));
                } else {
                    member = ((ObjectNode) field.getValue()).deepCopy();
                }
                String partDescription = override != null && override.hasNonNull("description")
                        ? override.get("description").asText()
                        : field.getValue().path("description").asText(null);
                if (isMultipart(type) && !isBinary(original) && !isBinaryArray(original)) {
                    String partType = partTypes(name, original, media, override).get(0);
                    if (partType.equals("application/json")) {
                        String note = "(sent as an application/json part)";
                        partDescription = partDescription == null ? note : stripDot(partDescription) + " " + note + ".";
                    }
                }
                if (partDescription != null) {
                    // description first, for readability
                    ObjectNode ordered = NODES.objectNode().put("description", partDescription);
                    member.fields().forEachRemaining(f -> {
                        if (!f.getKey().equals("description")) {
                            ordered.set(f.getKey(), f.getValue());
                        }
                    });
                    member = ordered;
                }
                members.set(name, member);
            });
            body.set("properties", members);
            if (converted.has("required")) {
                body.set("required", converted.get("required"));
            }
            body.put("additionalProperties", false);
            return body;
        }
        if (isBinary(shaper.dereference(media.schema())) || type.equals("application/octet-stream")) {
            ObjectNode file = fileObject(List.of(type));
            ObjectNode body = NODES.objectNode().put("description", "File sent as the request body (" + type + ").");
            file.fields().forEachRemaining(f -> body.set(f.getKey(), f.getValue()));
            return body;
        }
        ObjectNode body = shaper.inline(media.schema(), Direction.INPUT, defs);
        if (!body.hasNonNull("description")) {
            body.put("description", description);
        }
        return body;
    }

    /** The file object of tools-contract §9: one of the enabled forms. */
    ObjectNode fileObject(List<String> contentTypes) {
        List<ObjectNode> forms = new ArrayList<>();
        if (context.fileForms().contains("base64")) {
            ObjectNode props = NODES.objectNode();
            props.set("base64", NODES.objectNode().put("type", "string").put("contentEncoding", "base64"));
            props.set("filename", NODES.objectNode().put("type", "string"));
            ObjectNode contentType = NODES.objectNode().put("type", "string");
            if (contentTypes.stream().noneMatch(t -> t.contains("*"))) {
                ArrayNode allowed = NODES.arrayNode();
                contentTypes.forEach(allowed::add);
                contentType.set("enum", allowed);
            }
            props.set("contentType", contentType);
            forms.add(form(props, "base64"));
        }
        if (context.fileForms().contains("path")) {
            ObjectNode props = NODES.objectNode();
            props.set("path", NODES.objectNode().put("type", "string")
                    .put("description", "Path of the file, relative to the server's file root."));
            forms.add(form(props, "path"));
        }
        if (forms.size() == 1) {
            return forms.get(0);
        }
        ArrayNode oneOf = NODES.arrayNode();
        forms.forEach(oneOf::add);
        return NODES.objectNode().set("oneOf", oneOf);
    }

    private static ObjectNode form(ObjectNode properties, String required) {
        ObjectNode form = NODES.objectNode().put("type", "object");
        form.set("properties", properties);
        form.set("required", NODES.arrayNode().add(required));
        form.put("additionalProperties", false);
        return form;
    }

    // ---------------------------------------------------------------------- variants

    private ObjectNode variant(ToolPlan plan, Operation op, Contract contract) {
        SchemaShaper shaper = shaper(contract);
        String prefix = "#/schemas/" + contract.api() + "@" + contract.version() + "/";
        ObjectNode variant = NODES.objectNode().put("method", op.method()).put("path", op.path());

        ArrayNode parameters = NODES.arrayNode();
        for (String group : GROUPS) {
            for (Parameter p : visible(op, contract, group)) {
                ObjectNode param = NODES.objectNode()
                        .put("name", p.name())
                        .put("in", p.in())
                        .put("required", p.required() || group.equals("path"));
                if (p.style() != null) {
                    param.put("style", p.style());
                }
                param.put("explode", p.explode());
                param.set("schema", shaper.referencing(p.schema(), Direction.INPUT, prefix));
                parameters.add(param);
            }
        }
        variant.set("parameters", parameters);

        if (plan.mediaType() != null) {
            MediaType media = mediaType(op, plan.mediaType());
            ObjectNode body = NODES.objectNode()
                    .put("contentType", media.name())
                    .put("required", op.requestBody().required());
            body.set("schema", shaper.referencing(media.schema(), Direction.INPUT, prefix));
            if (isMultipart(media.name())) {
                JsonNode schema = shaper.dereference(media.schema());
                Set<String> requiredParts = new LinkedHashSet<>();
                schema.path("required").forEach(r -> requiredParts.add(r.asText()));
                ArrayNode parts = NODES.arrayNode();
                schema.path("properties").fields().forEachRemaining(field -> {
                    JsonNode property = shaper.dereference(field.getValue());
                    if (property.path("readOnly").asBoolean(false)) {
                        return;
                    }
                    ObjectNode override = plan.parts().get(field.getKey());
                    boolean file = isBinary(property) || isBinaryArray(property);
                    JsonNode typed = isBinaryArray(property) ? property.path("items") : property;
                    ArrayNode types = NODES.arrayNode();
                    partTypes(field.getKey(), typed, media, override).forEach(types::add);
                    ObjectNode part = NODES.objectNode().put("name", field.getKey());
                    part.set("contentTypes", types);
                    part.put("file", file);
                    part.put("secret", override != null && override.path("secret").asBoolean(false));
                    part.put("required", requiredParts.contains(field.getKey()));
                    parts.add(part);
                });
                body.set("parts", parts);
            }
            variant.set("body", body);
        }

        ArrayNode accept = NODES.arrayNode();
        (plan.accept() != null ? plan.accept() : acceptOf(op)).forEach(accept::add);
        variant.set("accept", accept);

        // an empty list is kept: it marks a public operation (no credentials, auth.md §2.2)
        List<List<SecurityRequirement>> security = op.security() != null ? op.security() : contract.security();
        if (security != null && (!security.isEmpty() || op.security() != null)) {
            ArrayNode alternatives = NODES.arrayNode();
            for (List<SecurityRequirement> all : security) {
                ArrayNode set = NODES.arrayNode();
                for (SecurityRequirement r : all) {
                    ObjectNode req = NODES.objectNode().put("scheme", r.scheme());
                    if (r.scopes() != null && !r.scopes().isEmpty()) {
                        ArrayNode scopes = NODES.arrayNode();
                        r.scopes().forEach(scopes::add);
                        req.set("scopes", scopes);
                    }
                    set.add(req);
                }
                alternatives.add(set);
            }
            variant.set("security", alternatives);
        }

        ArrayNode responses = NODES.arrayNode();
        for (Response r : op.responses()) {
            boolean error = r.status().equals("default") || r.status().startsWith("4") || r.status().startsWith("5");
            if (r.content() == null || r.content().isEmpty()) {
                ObjectNode response = NODES.objectNode().put("status", r.status());
                if (error) {
                    response.put("error", true);
                }
                responses.add(response);
                continue;
            }
            for (MediaType m : r.content()) {
                ObjectNode response = NODES.objectNode().put("status", r.status()).put("contentType", m.name());
                if (m.schema() != null && !m.schema().isMissingNode() && !m.schema().isNull()) {
                    response.set("schema", shaper.referencing(m.schema(), Direction.OUTPUT, prefix));
                }
                if (error) {
                    response.put("error", true);
                }
                responses.add(response);
            }
        }
        variant.set("responses", responses);
        return variant;
    }

    /** tools-contract §13.2: every response media type, in the spec's order, never collapsed. */
    static List<String> acceptOf(Operation op) {
        Set<String> types = new LinkedHashSet<>();
        op.responses().forEach(r -> {
            if (r.content() != null) {
                r.content().forEach(m -> types.add(m.name()));
            }
        });
        return List.copyOf(types);
    }

    // ---------------------------------------------------------------------- helpers

    /** The operation's parameters of one location, without auth and reserved ones. */
    private static List<Parameter> visible(Operation op, Contract contract, String group) {
        List<Parameter> result = new ArrayList<>();
        for (Parameter p : op.parameters()) {
            if (p.in().equals(group) && !isAuth(p, contract)) {
                result.add(p);
            }
        }
        return result;
    }

    private static boolean isAuth(Parameter p, Contract contract) {
        if (p.in().equals("header") && RESERVED_HEADERS.contains(p.name().toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (SecurityScheme scheme : contract.securitySchemes().values()) {
            if ("apiKey".equals(scheme.type()) && p.in().equals(scheme.in()) && scheme.parameterName() != null
                    && (p.in().equals("header")
                    ? scheme.parameterName().equalsIgnoreCase(p.name())
                    : scheme.parameterName().equals(p.name()))) {
                return true;
            }
        }
        return false;
    }

    /** tools-contract §13.4: encoding, then the rule's {@code parts.<name>.contentType}, then OpenAPI defaults. */
    private static List<String> partTypes(String name, JsonNode property, MediaType media, ObjectNode override) {
        List<String> encoded = media.encoding() == null ? null : media.encoding().get(name);
        if (encoded != null && !encoded.isEmpty()) {
            return encoded;
        }
        if (override != null && override.has("contentType")) {
            JsonNode types = override.get("contentType");
            List<String> result = new ArrayList<>();
            if (types.isArray()) {
                types.forEach(t -> result.add(t.asText()));
            } else {
                result.add(types.asText());
            }
            return result;
        }
        if (isBinary(property)) {
            return List.of(property.path("contentMediaType").asText("application/octet-stream"));
        }
        String type = typeOf(property);
        if (type.equals("object") || type.equals("array") || property.has("properties")) {
            return List.of("application/json");
        }
        return List.of("text/plain");
    }

    static boolean isBinary(JsonNode schema) {
        if (schema == null) {
            return false;
        }
        String type = typeOf(schema);
        return (type.equals("string") || type.equals("file") || type.isEmpty())
                && (schema.path("format").asText().equals("binary") || type.equals("file") || schema.has("contentMediaType"));
    }

    private static boolean isBinaryArray(JsonNode schema) {
        return typeOf(schema).equals("array") && isBinary(schema.path("items"));
    }

    private static String typeOf(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (!t.asText().equals("null")) {
                    return t.asText();
                }
            }
        }
        return type.asText("");
    }

    private static MediaType mediaType(Operation op, String name) {
        if (op.requestBody() == null) {
            return null;
        }
        return op.requestBody().content().stream().filter(m -> m.name().equals(name)).findFirst().orElse(null);
    }

    private static boolean isMultipart(String type) {
        return type.startsWith("multipart/");
    }

    private static boolean isForm(String type) {
        return type.equals("application/x-www-form-urlencoded");
    }

    private static SchemaShaper shaper(Contract contract) {
        return new SchemaShaper(contract.schemas(), contract.format());
    }

    private static List<String> contractOrder(List<String> versions, Set<String> order) {
        return order.stream().filter(versions::contains).toList();
    }

    private static String stripDot(String text) {
        String trimmed = text.strip();
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String last(Set<String> values) {
        String result = null;
        for (String v : values) {
            result = v;
        }
        return result;
    }
}
