package ro.bitboy.mcpgp.core.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.core.diag.Codes;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.diag.Location;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Contract;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Format;
import ro.bitboy.mcpgp.core.openapi.ApiModel.MediaType;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Operation;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Parameter;
import ro.bitboy.mcpgp.core.openapi.ApiModel.RequestBody;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Response;
import ro.bitboy.mcpgp.core.openapi.ApiModel.SecurityRequirement;
import ro.bitboy.mcpgp.core.openapi.ApiModel.SecurityScheme;
import ro.bitboy.mcpgp.core.resolve.Documents;
import ro.bitboy.mcpgp.core.resolve.ProjectPaths;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition.ContractFiles;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition.ContractKey;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loads one contract (an OpenAPI document plus its overlays) into {@link Contract}.
 *
 * <p>Swagger 2.0, OpenAPI 3.0 and 3.1 are supported; 3.2 is reported as not yet supported. External
 * {@code $ref}s are inlined after checking they stay inside the project root (recursive ones become shared
 * schemas); internal references to shared schemas stay as {@code #/components/schemas/<Name>}. Nothing is fetched from the network.
 */
public final class ContractLoader {

    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Path root;
    private final Path displayRoot;

    /**
     * @param root        the project root that contract files must stay inside
     * @param displayRoot the directory diagnostics paths are relative to
     */
    public ContractLoader(Path root, Path displayRoot) {
        this.root = root;
        this.displayRoot = displayRoot;
    }

    public Optional<Contract> load(ContractKey key, ContractFiles files, Diagnostics diagnostics) {
        Path file = files.spec();
        Location location = location(file);
        Optional<JsonNode> read = Documents.read(file, diagnostics, location);
        if (read.isEmpty()) {
            return Optional.empty();
        }
        if (!read.get().isObject()) {
            diagnostics.error(Codes.SPEC_INVALID, "Not an OpenAPI document", location);
            return Optional.empty();
        }
        ObjectNode document = (ObjectNode) read.get();

        for (Path overlayFile : files.overlays()) {
            Location overlayLocation = location(overlayFile);
            Documents.read(overlayFile, diagnostics, overlayLocation)
                    .ifPresent(overlay -> Overlays.apply(document, overlay, diagnostics, overlayLocation));
        }

        Optional<Format> format = format(document, diagnostics, location);
        if (format.isEmpty()) {
            return Optional.empty();
        }
        int before = diagnostics.errors().size();
        new ExternalRefs(diagnostics, file, document, format.get()).inline();
        if (diagnostics.errors().size() > before) {
            return Optional.empty();
        }
        if (format.get() == Format.SWAGGER_2_0) {
            rewriteRefs(document, "#/definitions/", "#/components/schemas/");
        }
        return Optional.of(new Reader(key, format.get(), document, location, diagnostics).contract());
    }

    // -------------------------------------------------------------------- format

    private Optional<Format> format(JsonNode document, Diagnostics diagnostics, Location location) {
        String swagger = document.path("swagger").asText("");
        String openapi = document.path("openapi").asText("");
        if (swagger.equals("2.0")) {
            return Optional.of(Format.SWAGGER_2_0);
        }
        if (openapi.startsWith("3.0.") || openapi.equals("3.0")) {
            return Optional.of(Format.OPENAPI_3_0);
        }
        if (openapi.startsWith("3.1.") || openapi.equals("3.1")) {
            return Optional.of(Format.OPENAPI_3_1);
        }
        if (openapi.startsWith("3.2")) {
            diagnostics.error(Codes.SPEC_VERSION_UNSUPPORTED, "OpenAPI " + openapi + " is not supported yet (planned)", location.withPointer("/openapi"));
            return Optional.empty();
        }
        diagnostics.error(Codes.SPEC_INVALID, "Not a Swagger 2.0 or OpenAPI 3.x document", location);
        return Optional.empty();
    }

    // -------------------------------------------------------------------- external references

    /**
     * Inlines every {@code $ref} that points to another file, checking each path. A schema that refers
     * back to itself through other files (a tree, a chain) can't be inlined: it becomes a shared schema,
     * named after the entry that already points to it or after its file, and the references to it point
     * there, as they would had it been written in the spec itself.
     */
    private final class ExternalRefs {
        private final Diagnostics diagnostics;
        private final Path main;
        private final ObjectNode document;
        private final Format format;
        private final Deque<String> stack = new ArrayDeque<>();
        private final Map<Path, JsonNode> cache = new HashMap<>();
        /** Shared schemas that point to another file, by what they point to. */
        private final Map<String, String> named = new HashMap<>();
        /** What became shared schemas: names by what they are, and their content. */
        private final Map<String, String> recursive = new HashMap<>();
        private final Map<String, JsonNode> hoisted = new LinkedHashMap<>();

        ExternalRefs(Diagnostics diagnostics, Path main, ObjectNode document, Format format) {
            this.diagnostics = diagnostics;
            this.main = main;
            this.document = document;
            this.format = format;
            shared().fields().forEachRemaining(f -> {
                String ref = f.getValue().path("$ref").asText("");
                if (f.getValue().size() == 1 && !ref.isEmpty() && !ref.startsWith("#") && !ProjectPaths.isRemote(ref)) {
                    int hash = ref.indexOf('#');
                    try {
                        Path target = main.getParent().resolve(hash < 0 ? ref : ref.substring(0, hash)).normalize();
                        named.putIfAbsent(target + "#" + (hash < 0 ? "" : ref.substring(hash + 1)), f.getKey());
                    } catch (RuntimeException e) {
                        // reported when it's resolved
                    }
                }
            });
        }

        void inline() {
            inline(document, main, "");
            if (!hoisted.isEmpty()) {
                ObjectNode shared = swagger() ? document.with("definitions") : document.with("components").with("schemas");
                hoisted.forEach(shared::set);
            }
        }

        private boolean swagger() {
            return format == Format.SWAGGER_2_0;
        }

        private JsonNode shared() {
            return swagger() ? document.path("definitions") : document.path("components").path("schemas");
        }

        private JsonNode inline(JsonNode node, Path file, String pointer) {
            if (node.isObject()) {
                ObjectNode object = (ObjectNode) node;
                JsonNode ref = object.get("$ref");
                // inside another file, its own "#/..." references are to that file
                if (ref != null && ref.isTextual() && (!ref.asText().startsWith("#") || !file.equals(main))) {
                    return resolve(ref.asText(), file, location(file).withPointer(pointer + "/$ref")).orElse(object);
                }
                List<String> names = new ArrayList<>();
                object.fieldNames().forEachRemaining(names::add);
                for (String name : names) {
                    object.set(name, inline(object.get(name), file, pointer + "/" + escape(name)));
                }
            } else if (node.isArray()) {
                ArrayNode array = (ArrayNode) node;
                for (int i = 0; i < array.size(); i++) {
                    array.set(i, inline(array.get(i), file, pointer + "/" + i));
                }
            }
            return node;
        }

        private Optional<JsonNode> resolve(String ref, Path from, Location location) {
            int hash = ref.indexOf('#');
            String filePart = hash < 0 ? ref : ref.substring(0, hash);
            String fragment = hash < 0 ? "" : ref.substring(hash + 1);
            Optional<Path> target = filePart.isEmpty() ? Optional.of(from)
                    : ProjectPaths.resolveLocal(filePart, from, root, diagnostics, location);
            if (target.isEmpty()) {
                return Optional.empty();
            }
            String id = target.get() + "#" + fragment;
            if (recursive.containsKey(id)) {
                return Optional.of(sharedRef(recursive.get(id)));
            }
            if (stack.contains(id)) {
                recursive.put(id, name(id, target.get(), fragment));
                return Optional.of(sharedRef(recursive.get(id)));
            }
            JsonNode file = cache.computeIfAbsent(target.get(),
                    p -> Documents.read(p, diagnostics, location(p)).orElse(NODES.missingNode()));
            if (file.isMissingNode()) {
                return Optional.empty();
            }
            JsonNode node = fragment.isEmpty() ? file : file.at(fragment);
            if (node.isMissingNode()) {
                diagnostics.error(Codes.REF_UNRESOLVED, "'" + ref + "' does not exist", location);
                return Optional.empty();
            }
            stack.push(id);
            JsonNode inlined;
            try {
                inlined = inline(node.deepCopy(), target.get(), fragment);
            } finally {
                stack.pop();
            }
            String name = recursive.get(id);
            if (name == null) {
                return Optional.of(inlined);
            }
            hoisted.put(name, inlined);
            return Optional.of(sharedRef(name));
        }

        private ObjectNode sharedRef(String name) {
            return NODES.objectNode().put("$ref", (swagger() ? "#/definitions/" : "#/components/schemas/") + name);
        }

        /** The entry that points to it, else its last pointer segment or file name in PascalCase, made unique. */
        private String name(String id, Path file, String fragment) {
            if (named.containsKey(id)) {
                return named.get(id);
            }
            String last = fragment.isEmpty() || fragment.equals("/") ? file.getFileName().toString().replaceFirst("\\.[^.]*$", "")
                    : fragment.substring(fragment.lastIndexOf('/') + 1).replace("~1", "/").replace("~0", "~");
            StringBuilder base = new StringBuilder();
            for (String word : last.split("[^A-Za-z0-9]+")) {
                if (!word.isEmpty()) {
                    base.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
                }
            }
            String name = base.isEmpty() ? "Schema" : base.toString();
            for (int i = 2; shared().has(name) || recursive.containsValue(name); i++) {
                name = base + String.valueOf(i);
            }
            return name;
        }
    }

    private static void rewriteRefs(JsonNode node, String from, String to) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            JsonNode ref = object.get("$ref");
            if (ref != null && ref.isTextual() && ref.asText().startsWith(from)) {
                object.put("$ref", to + ref.asText().substring(from.length()));
            }
            object.elements().forEachRemaining(child -> rewriteRefs(child, from, to));
        } else if (node.isArray()) {
            node.forEach(child -> rewriteRefs(child, from, to));
        }
    }

    // -------------------------------------------------------------------- model building

    /** Builds the model from one (already overlaid and inlined) document. */
    private static final class Reader {
        private final ContractKey key;
        private final Format format;
        private final ObjectNode document;
        private final Location location;
        private final Diagnostics diagnostics;
        private final Set<String> reportedFeatures = new LinkedHashSet<>();

        Reader(ContractKey key, Format format, ObjectNode document, Location location, Diagnostics diagnostics) {
            this.key = key;
            this.format = format;
            this.document = document;
            this.location = location;
            this.diagnostics = diagnostics;
        }

        boolean swagger() {
            return format == Format.SWAGGER_2_0;
        }

        Contract contract() {
            if (document.has("webhooks")) {
                unsupported("webhooks", "/webhooks");
            }
            Map<String, ObjectNode> schemas = new LinkedHashMap<>();
            JsonNode shared = swagger() ? document.path("definitions") : document.path("components").path("schemas");
            shared.fields().forEachRemaining(f -> {
                if (f.getValue().isObject()) {
                    schemas.put(f.getKey(), (ObjectNode) f.getValue());
                }
            });

            List<Operation> operations = new ArrayList<>();
            Map<String, String> operationIds = new HashMap<>();
            JsonNode paths = document.path("paths");
            Iterator<Map.Entry<String, JsonNode>> pathItems = paths.fields();
            while (pathItems.hasNext()) {
                Map.Entry<String, JsonNode> entry = pathItems.next();
                String path = entry.getKey();
                if (!path.startsWith("/")) {
                    continue; // x- extensions on the paths object
                }
                String itemPointer = "/paths/" + escape(path);
                JsonNode item = deref(entry.getValue(), itemPointer);
                List<Parameter> shared0 = parameters(item.path("parameters"), itemPointer + "/parameters");
                for (String method : METHODS) {
                    JsonNode op = item.get(method);
                    if (op == null || !op.isObject()) {
                        continue;
                    }
                    String pointer = itemPointer + "/" + method;
                    Operation operation = operation(method.toUpperCase(), path, op, shared0, pointer);
                    operations.add(operation);
                    if (operation.operationId() != null) {
                        String previous = operationIds.putIfAbsent(operation.operationId(), operation.key());
                        if (previous != null) {
                            diagnostics.warning(Codes.OPERATION_ID_DUPLICATE,
                                    "operationId '" + operation.operationId() + "' is also used by " + previous,
                                    location.withPointer(pointer + "/operationId"));
                        }
                    }
                }
            }
            return new Contract(key.api(), key.version(), format,
                    document.path("info").path("version").asText(""), basePath(),
                    List.copyOf(operations), securitySchemes(), security(document.get("security")),
                    Map.copyOf(schemas), location.file());
        }

        private Operation operation(String method, String path, JsonNode op, List<Parameter> shared, String pointer) {
            if (op.has("callbacks")) {
                unsupported("callbacks", pointer + "/callbacks");
            }
            List<Parameter> own = parameters(op.path("parameters"), pointer + "/parameters");
            Map<String, Parameter> merged = new LinkedHashMap<>();
            shared.forEach(p -> merged.put(p.in() + ":" + p.name(), p));
            own.forEach(p -> merged.put(p.in() + ":" + p.name(), p));
            List<Parameter> parameters = merged.values().stream()
                    .filter(p -> !p.in().equals("body") && !p.in().equals("formData"))
                    .toList();

            RequestBody body = swagger()
                    ? swaggerBody(op, new ArrayList<>(merged.values()))
                    : openApiBody(op.get("requestBody"), pointer + "/requestBody");

            List<String> tags = new ArrayList<>();
            op.path("tags").forEach(t -> tags.add(t.asText()));
            Map<String, JsonNode> extensions = new LinkedHashMap<>();
            op.fields().forEachRemaining(f -> {
                if (f.getKey().startsWith("x-")) {
                    extensions.put(f.getKey(), f.getValue());
                }
            });
            return new Operation(
                    key.api() + ":" + method + ":" + path, method, path,
                    textOrNull(op, "operationId"), List.copyOf(tags),
                    textOrNull(op, "summary"), textOrNull(op, "description"),
                    op.path("deprecated").asBoolean(false),
                    parameters, body, responses(op, pointer + "/responses"),
                    op.has("security") ? security(op.get("security")) : null,
                    Map.copyOf(extensions), pointer);
        }

        // ---------------------------------------------------------------- parameters

        private List<Parameter> parameters(JsonNode list, String pointer) {
            List<Parameter> result = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                String p = pointer + "/" + i;
                JsonNode param = deref(list.get(i), p);
                String in = param.path("in").asText();
                String name = param.path("name").asText();
                if (name.isEmpty() || in.isEmpty()) {
                    diagnostics.error(Codes.SPEC_INVALID, "Parameter without name or 'in'", location.withPointer(p));
                    continue;
                }
                JsonNode schema;
                String style;
                boolean explode;
                if (swagger()) {
                    schema = in.equals("body") ? param.path("schema") : swaggerSchema(param);
                    String collection = param.path("collectionFormat").asText("csv");
                    style = switch (collection) {
                        case "ssv" -> "spaceDelimited";
                        case "pipes" -> "pipeDelimited";
                        default -> in.equals("query") || in.equals("formData") ? "form" : "simple";
                    };
                    explode = collection.equals("multi");
                    if (collection.equals("tsv")) {
                        unsupported("collectionFormat tsv", p + "/collectionFormat");
                    }
                } else {
                    schema = param.has("schema") ? param.get("schema") : firstContentSchema(param.path("content"));
                    style = param.path("style").asText(in.equals("query") || in.equals("cookie") ? "form" : "simple");
                    explode = param.has("explode") ? param.get("explode").asBoolean() : style.equals("form");
                }
                result.add(new Parameter(name, in, param.path("required").asBoolean(in.equals("path")),
                        textOrNull(param, "description"), schema == null ? NODES.objectNode() : schema,
                        style, explode, param.path("deprecated").asBoolean(false), p));
            }
            return result;
        }

        /** Swagger 2.0 non-body parameters carry their schema inline. */
        private static JsonNode swaggerSchema(JsonNode param) {
            ObjectNode schema = NODES.objectNode();
            for (String field : List.of("type", "format", "items", "enum", "default", "minimum", "maximum",
                    "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "pattern", "minItems", "maxItems", "uniqueItems", "multipleOf")) {
                if (param.has(field)) {
                    schema.set(field, param.get(field).deepCopy());
                }
            }
            if (schema.path("type").asText().equals("file")) {
                schema.put("type", "string");
                schema.put("format", "binary");
            }
            return schema;
        }

        // ---------------------------------------------------------------- bodies

        private RequestBody openApiBody(JsonNode node, String pointer) {
            if (node == null) {
                return null;
            }
            JsonNode body = deref(node, pointer);
            return new RequestBody(body.path("required").asBoolean(false), textOrNull(body, "description"),
                    mediaTypes(body.path("content")));
        }

        private RequestBody swaggerBody(JsonNode op, List<Parameter> parameters) {
            List<String> consumes = mediaList(op.path("consumes"), document.path("consumes"), "application/json");
            Optional<Parameter> bodyParam = parameters.stream().filter(p -> p.in().equals("body")).findFirst();
            if (bodyParam.isPresent()) {
                List<MediaType> content = new ArrayList<>();
                for (String media : consumes) {
                    if (!isForm(media)) {
                        content.add(new MediaType(media, bodyParam.get().schema(), Map.of()));
                    }
                }
                return new RequestBody(bodyParam.get().required(), bodyParam.get().description(), content);
            }
            List<Parameter> form = parameters.stream().filter(p -> p.in().equals("formData")).toList();
            if (form.isEmpty()) {
                return null;
            }
            ObjectNode schema = NODES.objectNode();
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            ArrayNode required = NODES.arrayNode();
            boolean hasFile = false;
            for (Parameter p : form) {
                ObjectNode property = p.schema().deepCopy();
                if (p.description() != null) {
                    property.put("description", p.description());
                }
                properties.set(p.name(), property);
                if (p.required()) {
                    required.add(p.name());
                }
                hasFile |= property.path("format").asText().equals("binary");
            }
            if (!required.isEmpty()) {
                schema.set("required", required);
            }
            List<MediaType> content = new ArrayList<>();
            for (String media : consumes) {
                if (isForm(media)) {
                    content.add(new MediaType(media, schema, Map.of()));
                }
            }
            if (content.isEmpty()) {
                content.add(new MediaType(hasFile ? "multipart/form-data" : "application/x-www-form-urlencoded", schema, Map.of()));
            }
            return new RequestBody(!required.isEmpty(), null, content);
        }

        private static boolean isForm(String media) {
            return media.startsWith("multipart/form-data") || media.startsWith("application/x-www-form-urlencoded");
        }

        private List<MediaType> mediaTypes(JsonNode content) {
            List<MediaType> result = new ArrayList<>();
            content.fields().forEachRemaining(f -> {
                Map<String, List<String>> encoding = new LinkedHashMap<>();
                f.getValue().path("encoding").fields().forEachRemaining(e -> {
                    String types = e.getValue().path("contentType").asText("");
                    if (!types.isBlank()) {
                        List<String> list = new ArrayList<>();
                        for (String t : types.split(",")) {
                            list.add(t.trim());
                        }
                        encoding.put(e.getKey(), List.copyOf(list));
                    }
                });
                result.add(new MediaType(f.getKey(), f.getValue().path("schema").isMissingNode() ? null : f.getValue().get("schema"),
                        Map.copyOf(encoding), example(f.getValue())));
            });
            return List.copyOf(result);
        }

        /** A media type's {@code example}, else the value of the first of its {@code examples}. */
        private static JsonNode example(JsonNode media) {
            if (media.has("example")) {
                return media.get("example");
            }
            Iterator<JsonNode> examples = media.path("examples").elements();
            return examples.hasNext() ? examples.next().get("value") : null;
        }

        private static JsonNode firstContentSchema(JsonNode content) {
            Iterator<JsonNode> media = content.elements();
            return media.hasNext() ? media.next().get("schema") : null;
        }

        // ---------------------------------------------------------------- responses

        private List<Response> responses(JsonNode op, String pointer) {
            List<Response> result = new ArrayList<>();
            JsonNode responses = op.path("responses");
            List<String> produces = mediaList(op.path("produces"), document.path("produces"), "application/json");
            responses.fields().forEachRemaining(f -> {
                if (f.getKey().startsWith("x-")) {
                    return;
                }
                String p = pointer + "/" + escape(f.getKey());
                JsonNode response = deref(f.getValue(), p);
                if (response.has("links")) {
                    unsupported("links", p + "/links");
                }
                List<MediaType> content;
                if (swagger()) {
                    content = new ArrayList<>();
                    if (response.has("schema")) {
                        for (String media : produces) {
                            content.add(new MediaType(media, response.get("schema"), Map.of(), response.path("examples").get(media)));
                        }
                    }
                } else {
                    content = mediaTypes(response.path("content"));
                }
                result.add(new Response(f.getKey(), textOrNull(response, "description"), List.copyOf(content)));
            });
            return List.copyOf(result);
        }

        private static List<String> mediaList(JsonNode own, JsonNode global, String fallback) {
            JsonNode list = own.isArray() && !own.isEmpty() ? own : global;
            List<String> result = new ArrayList<>();
            if (list.isArray()) {
                list.forEach(m -> result.add(m.asText()));
            }
            if (result.isEmpty()) {
                result.add(fallback);
            }
            return result;
        }

        // ---------------------------------------------------------------- security

        private Map<String, SecurityScheme> securitySchemes() {
            Map<String, SecurityScheme> result = new LinkedHashMap<>();
            JsonNode schemes = swagger() ? document.path("securityDefinitions") : document.path("components").path("securitySchemes");
            schemes.fields().forEachRemaining(f -> {
                JsonNode s = deref(f.getValue(), "/components/securitySchemes/" + escape(f.getKey()));
                String type = s.path("type").asText();
                List<String> scopes = new ArrayList<>();
                String tokenUrl = null;
                if (swagger()) {
                    if (type.equals("basic")) {
                        result.put(f.getKey(), new SecurityScheme(f.getKey(), "http", "basic", null, null, List.of(), null));
                        return;
                    }
                    s.path("scopes").fieldNames().forEachRemaining(scopes::add);
                    tokenUrl = textOrNull(s, "tokenUrl");
                } else {
                    Iterator<JsonNode> flows = s.path("flows").elements();
                    while (flows.hasNext()) {
                        JsonNode flow = flows.next();
                        flow.path("scopes").fieldNames().forEachRemaining(name -> {
                            if (!scopes.contains(name)) {
                                scopes.add(name);
                            }
                        });
                        if (tokenUrl == null) {
                            tokenUrl = textOrNull(flow, "tokenUrl");
                        }
                    }
                }
                result.put(f.getKey(), new SecurityScheme(f.getKey(), type, textOrNull(s, "scheme"), textOrNull(s, "in"),
                        textOrNull(s, "name"), List.copyOf(scopes), tokenUrl));
            });
            return Map.copyOf(result);
        }

        private static List<List<SecurityRequirement>> security(JsonNode node) {
            if (node == null || !node.isArray()) {
                return node == null ? null : List.of();
            }
            List<List<SecurityRequirement>> alternatives = new ArrayList<>();
            for (JsonNode alternative : node) {
                List<SecurityRequirement> all = new ArrayList<>();
                alternative.fields().forEachRemaining(f -> {
                    List<String> scopes = new ArrayList<>();
                    f.getValue().forEach(s -> scopes.add(s.asText()));
                    all.add(new SecurityRequirement(f.getKey(), List.copyOf(scopes)));
                });
                alternatives.add(List.copyOf(all));
            }
            return List.copyOf(alternatives);
        }

        // ---------------------------------------------------------------- base path

        private String basePath() {
            if (swagger()) {
                return trimSlash(document.path("basePath").asText(""));
            }
            JsonNode server = document.path("servers").path(0);
            if (server.isMissingNode()) {
                return "";
            }
            String url = server.path("url").asText("");
            Iterator<Map.Entry<String, JsonNode>> variables = server.path("variables").fields();
            while (variables.hasNext()) {
                Map.Entry<String, JsonNode> v = variables.next();
                url = url.replace("{" + v.getKey() + "}", v.getValue().path("default").asText(""));
            }
            try {
                String path = url.contains("://") ? URI.create(url).getPath() : url;
                return trimSlash(path == null ? "" : path);
            } catch (IllegalArgumentException e) {
                diagnostics.warning(Codes.SPEC_INVALID, "servers[0].url is not a valid URL; no base path used", location.withPointer("/servers/0/url"));
                return "";
            }
        }

        private static String trimSlash(String path) {
            String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            return p.isEmpty() || p.startsWith("/") ? p : "/" + p;
        }

        // ---------------------------------------------------------------- helpers

        /** Follows internal {@code $ref}s of non-schema objects (parameters, bodies, responses, path items). */
        private JsonNode deref(JsonNode node, String pointer) {
            JsonNode current = node;
            Set<String> seen = new HashSet<>();
            while (current.isObject() && current.has("$ref") && current.get("$ref").isTextual()) {
                String ref = current.get("$ref").asText();
                if (!ref.startsWith("#") || !seen.add(ref)) {
                    diagnostics.error(Codes.REF_UNRESOLVED, "Cannot follow '" + ref + "'", location.withPointer(pointer));
                    return NODES.objectNode();
                }
                String target = ref.substring(1);
                if (swagger()) {
                    target = target.replaceFirst("^/components/schemas/", "/definitions/");
                }
                JsonNode next = document.at(target);
                if (next.isMissingNode()) {
                    diagnostics.error(Codes.REF_UNRESOLVED, "'" + ref + "' does not exist", location.withPointer(pointer));
                    return NODES.objectNode();
                }
                current = next;
            }
            return current;
        }

        private void unsupported(String feature, String pointer) {
            if (reportedFeatures.add(feature)) {
                diagnostics.info(Codes.UNSUPPORTED_FEATURE, "Not supported, ignored: " + feature, location.withPointer(pointer));
            }
        }

        private static String textOrNull(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || value.isNull() ? null : value.asText();
        }
    }

    private Location location(Path file) {
        return Location.of(displayRoot.relativize(file).toString().replace('\\', '/'));
    }

    static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
