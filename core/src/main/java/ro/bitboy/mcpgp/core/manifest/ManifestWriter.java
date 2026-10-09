package ro.bitboy.mcpgp.core.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.core.diag.Codes;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.diag.Location;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Contract;
import ro.bitboy.mcpgp.core.openapi.ApiModel.SecurityScheme;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition.ContractFiles;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition.ContractKey;
import ro.bitboy.mcpgp.core.schema.SchemaShaper;
import ro.bitboy.mcpgp.core.schema.SchemaShaper.Direction;
import ro.bitboy.mcpgp.core.schema.ToolShaper;
import ro.bitboy.mcpgp.core.tools.BuiltinNames;
import ro.bitboy.mcpgp.core.tools.NamesLock;
import ro.bitboy.mcpgp.core.tools.ToolPlan;
import ro.bitboy.mcpgp.core.tools.ToolPlan.Provenance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Assembles the manifest (manifest.md) from a resolved definition, its contracts and tool plans.
 * The result is deterministic: the same inputs and {@link Build} produce the same canonical JSON.
 */
public final class ManifestWriter {

    public static final String MANIFEST_VERSION = "1.0";
    /** MCP revisions this compiler targets (compatibility.md §1). */
    static final List<String> MCP_REVISIONS = List.of("2025-03-26", "2025-06-18", "2025-11-25");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Map<String, String> DEFAULT_SUFFIXES = new TreeMap<>(Map.of(
            "multipart/form-data", "multipart",
            "application/x-www-form-urlencoded", "form",
            "application/octet-stream", "binary"));

    /**
     * Build inputs that don't come from the definition.
     *
     * @param createdAt   the build time (from {@code SOURCE_DATE_EPOCH} or the commit time)
     * @param cliVersion  the compiler's version
     * @param version     explicit server version ({@code --version}); null to follow the default contract
     * @param buildNumber added as SemVer build metadata ({@code 2.0.0+3}); null for none
     * @param source      where the definition came from (e.g. {@code git+https://…#<commit>}); null if unknown
     */
    public record Build(Instant createdAt, String cliVersion, String version, Integer buildNumber, String source) {
    }

    private final Diagnostics diagnostics;

    public ManifestWriter(Diagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    /** The manifest; its {@code diagnostics} are empty until {@link #withDiagnostics} fills them in. */
    public ObjectNode write(ResolvedDefinition definition, Map<ContractKey, Contract> contracts, List<ToolPlan> plans,
                            Build build) {
        JsonNode doc = definition.document();
        Location root = Location.of(relative(definition, definition.file()));
        boolean multi = doc.path("instances").path("mode").asText("single").equals("multi");
        List<JsonNode> apis = new ArrayList<>();
        doc.path("apis").forEach(apis::add);
        boolean severalApis = apis.size() > 1;
        List<String> fileForms = new ArrayList<>();
        doc.path("files").path("forms").forEach(f -> fileForms.add(f.asText()));
        if (!doc.path("files").has("forms")) {
            fileForms.addAll(List.of("base64", "path"));
        }

        ObjectNode manifest = NODES.objectNode().put("manifestVersion", MANIFEST_VERSION);
        manifest.set("server", server(doc, multi, severalApis, build));
        manifest.set("build", build(definition, build));
        manifest.set("runtime", runtime(doc, build));
        manifest.set("instances", NODES.objectNode()
                .put("mode", multi ? "multi" : "single")
                .put("mixedContracts", doc.path("instances").path("mixedContracts").asText("union"))
                .set("max", doc.path("instances").path("max").isMissingNode() ? NODES.nullNode() : doc.path("instances").get("max")));

        ArrayNode apiNodes = NODES.arrayNode();
        ObjectNode schemas = NODES.objectNode();
        Map<String, Map<String, Contract>> contractsByApi = new LinkedHashMap<>();
        Map<String, String> defaultVersions = new LinkedHashMap<>();
        for (JsonNode api : apis) {
            String id = api.path("id").asText();
            Map<String, Contract> versions = new LinkedHashMap<>();
            api.path("contracts").forEach(c -> {
                Contract contract = contracts.get(new ContractKey(id, c.path("version").asText()));
                if (contract != null) {
                    versions.put(contract.version(), contract);
                }
            });
            contractsByApi.put(id, versions);
            defaultVersions.put(id, defaultVersion(api));
            apiNodes.add(api(definition, api, versions, defaultVersions.get(id)));
            versions.forEach((version, contract) -> {
                SchemaShaper shaper = new SchemaShaper(contract.schemas(), contract.format());
                ObjectNode shared = NODES.objectNode();
                new TreeMap<>(contract.schemas()).forEach((name, schema) ->
                        shared.set(name, shaper.referencing(schema, Direction.BOTH, "#/schemas/" + id + "@" + version + "/")));
                schemas.set(id + "@" + version, shared);
            });
        }
        manifest.set("apis", apiNodes);

        // tools
        BuiltinNames names = new BuiltinNames(doc.path("naming"));
        List<ObjectNode> tools = new ArrayList<>();
        for (ToolPlan plan : plans) {
            JsonNode api = apis.stream().filter(a -> a.path("id").asText().equals(plan.api())).findFirst().orElseThrow();
            String apiTitle = api.path("title").asText(plan.api());
            ToolShaper shaper = new ToolShaper(new ToolShaper.Context(multi,
                    "Target " + apiTitle + " server. Filled in at startup from the runtime config.", fileForms));
            ToolShaper.Shaped shaped = shaper.shape(plan, contractsByApi.get(plan.api()), defaultVersions.get(plan.api()));
            Contract primary = contractsByApi.get(plan.api()).getOrDefault(defaultVersions.get(plan.api()),
                    contractsByApi.get(plan.api()).values().iterator().next());
            tools.add(apiTool(plan, shaped, primary.file()));
        }
        List<Builtins.Spec> builtins = new ArrayList<>();
        if (multi) {
            apis.forEach(api -> builtins.add(Builtins.instancesList(api.path("title").asText(api.path("id").asText()))));
        }
        JsonNode exposure = doc.path("exposure");
        String mode = exposure.path("mode").asText("plain");
        String style = exposure.path("discoveryStyle").asText("search");
        List<Builtins.Spec> exposureTools = switch (mode) {
            case "discovery", "hybrid" -> Builtins.discovery(style, multi);
            case "toolsets" -> Builtins.toolsets();
            default -> exposure.path("plainSearch").asBoolean(false) ? Builtins.plainSearch(multi) : List.of();
        };
        List<ObjectNode> builtinTools = new ArrayList<>();
        int apiIndex = 0;
        for (Builtins.Spec spec : builtins) {
            String prefix = severalApis ? prefix(apis.get(apiIndex++)) : null;
            builtinTools.add(named(names.name(spec.base(), prefix), spec.tool()));
        }
        for (Builtins.Spec spec : exposureTools) {
            builtinTools.add(named(names.name(spec.base(), null), spec.tool()));
        }
        tools.addAll(builtinTools);

        // packs: in single-API servers, pack tools carry the API prefix like API tools
        String packPrefix = severalApis ? null : prefix(apis.get(0));
        ArrayNode packs = NODES.arrayNode();
        doc.path("packs").fields().forEachRemaining(entry -> {
            if (entry.getValue().isNull()) {
                return;
            }
            Builtins.Pack pack = Builtins.pack(entry.getKey(), multi);
            if (pack == null) {
                diagnostics.warning(Codes.PACK_UNKNOWN, "Pack '" + entry.getKey() + "' is not available in this version; it is left out",
                        root.withPointer("/packs/" + entry.getKey()));
                return;
            }
            ArrayNode packTools = NODES.arrayNode();
            for (Builtins.Spec spec : pack.tools()) {
                String name = names.name(spec.base(), packPrefix);
                packTools.add(name);
                tools.add(named(name, spec.tool()));
            }
            packs.add(NODES.objectNode().put("id", entry.getKey()).put("version", pack.version())
                    .<ObjectNode>set("tools", packTools)
                    .set("requires", NODES.objectNode().put("config", false)));
        });

        tools.sort(Comparator.comparing(t -> t.path("name").asText()));
        checkUnique(tools, root);
        ArrayNode toolNodes = NODES.arrayNode();
        tools.forEach(toolNodes::add);

        ObjectNode exposureNode = NODES.objectNode().put("mode", mode);
        if (mode.equals("discovery") || mode.equals("hybrid")) {
            exposureNode.put("discoveryStyle", style);
        }
        // pinned tools only matter in hybrid mode (a variant may inherit a list it doesn't use)
        if (mode.equals("hybrid") && exposure.has("pinned")) {
            Set<String> known = new LinkedHashSet<>();
            tools.forEach(t -> known.add(t.path("name").asText()));
            for (int i = 0; i < exposure.get("pinned").size(); i++) {
                String pinned = exposure.get("pinned").get(i).asText();
                if (!known.contains(pinned)) {
                    diagnostics.error(Codes.PINNED_TOOL_UNKNOWN, "Pinned tool '" + pinned + "' is not produced by this build",
                            root.withPointer("/exposure/pinned/" + i));
                }
            }
            exposureNode.set("pinned", exposure.get("pinned").deepCopy());
        }
        exposureNode.put("plainSearch", exposure.path("plainSearch").asBoolean(false));
        if (exposure.has("toolsets")) {
            exposureNode.set("toolsets", exposure.get("toolsets").deepCopy());
        }
        manifest.set("exposure", exposureNode);
        manifest.set("policies", policies(doc, fileForms));
        manifest.set("tools", toolNodes);
        manifest.set("packs", packs);
        manifest.set("schemas", schemas);
        manifest.set("diagnostics", NODES.arrayNode());
        return (ObjectNode) CanonicalJson.sorted(manifest);
    }

    /** The manifest with its {@code diagnostics} replaced by the warnings and info of {@code all}. */
    public ObjectNode withDiagnostics(ObjectNode manifest, List<Diagnostic> all) {
        ObjectNode copy = manifest.deepCopy();
        copy.set("diagnostics", diagnosticNodes(all));
        return (ObjectNode) CanonicalJson.sorted(copy);
    }

    // ---------------------------------------------------------------------- sections

    private static ObjectNode server(JsonNode doc, boolean multi, boolean severalApis, Build build) {
        JsonNode metadata = doc.path("metadata");
        ObjectNode server = NODES.objectNode().put("name", metadata.path("name").asText());
        if (metadata.hasNonNull("title")) {
            server.put("title", metadata.get("title").asText());
        }
        server.put("version", version(doc, build));
        String instructions = metadata.path("description").asText("").strip().replaceAll("\\s+", " ");
        if (multi) {
            String list = severalApis ? "the <api>_instances_list tools" : "instances_list";
            instructions = (instructions.isEmpty() ? "" : instructions + " ")
                    + "Every tool requires `instance`; call " + list + " to see the configured servers.";
        }
        if (!instructions.isEmpty()) {
            server.put("instructions", instructions);
        }
        return server;
    }

    private ObjectNode build(ResolvedDefinition definition, Build build) {
        ObjectNode node = NODES.objectNode()
                .put("createdAt", build.createdAt().truncatedTo(ChronoUnit.SECONDS).toString())
                .put("cli", build.cliVersion());
        ObjectNode def = NODES.objectNode().put("name", definition.document().path("metadata").path("name").asText());
        if (build.source() != null) {
            def.put("source", build.source());
        }
        def.put("digest", filesDigest(definition, definition.files()));
        node.set("definition", def);
        Path lock = definition.root().resolve(NamesLock.FILE_NAME);
        node.set("lock", Files.isRegularFile(lock)
                ? NODES.objectNode().put("digest", CanonicalJson.digest(read(lock)))
                : NODES.nullNode());
        if (definition.parent() != null) {
            String parent = definition.parent();
            int at = parent.lastIndexOf('@');
            String name = at < 0 ? parent : parent.substring(0, at);
            name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\.mcpgp\\.ya?ml$", "");
            node.set("extends", NODES.objectNode().put("name", name).put("version", at < 0 ? "local" : parent.substring(at + 1)));
        } else {
            node.set("extends", NODES.nullNode());
        }
        return node;
    }

    private static ObjectNode runtime(JsonNode doc, Build build) {
        JsonNode defaults = doc.path("runtimeDefaults");
        ObjectNode runtime = NODES.objectNode().put("minRuntime", build.cliVersion());
        ArrayNode revisions = NODES.arrayNode();
        MCP_REVISIONS.forEach(revisions::add);
        runtime.set("mcpRevisions", revisions);
        runtime.set("transports", defaults.has("transports") ? defaults.get("transports").deepCopy()
                : allowed(List.of("stdio", "http"), "stdio"));
        runtime.set("inboundAuth", defaults.has("inboundAuth") ? defaults.get("inboundAuth").deepCopy()
                : allowed(List.of("none", "static", "oauth"), "none"));
        return runtime;
    }

    private ObjectNode api(ResolvedDefinition definition, JsonNode api, Map<String, Contract> versions, String defaultVersion) {
        String id = api.path("id").asText();
        ObjectNode node = NODES.objectNode().put("id", id);
        if (api.hasNonNull("title")) {
            node.put("title", api.get("title").asText());
        }
        node.put("toolPrefix", prefix(api));
        ArrayNode contractNodes = NODES.arrayNode();
        versions.forEach((version, contract) -> {
            ContractFiles files = definition.contracts().get(new ContractKey(id, version));
            List<Path> all = new ArrayList<>();
            all.add(files.spec());
            all.addAll(files.overlays());
            contractNodes.add(NODES.objectNode()
                    .put("version", version)
                    .put("format", contract.format().id())
                    .put("digest", files.overlays().isEmpty() ? CanonicalJson.digest(read(files.spec())) : filesDigest(definition, all))
                    .put("basePath", contract.basePath())
                    .put("default", version.equals(defaultVersion)));
        });
        node.set("contracts", contractNodes);
        if (api.has("detect")) {
            ObjectNode detect = api.get("detect").deepCopy();
            if (!detect.has("method")) {
                detect.put("method", "GET");
            }
            node.set("detect", detect);
        }

        // downstream auth: declared, or derived from the contracts' security schemes
        JsonNode declared = api.path("downstreamAuth");
        ObjectNode auth = NODES.objectNode();
        Set<String> supported = new LinkedHashSet<>();
        declared.path("supported").forEach(s -> supported.add(s.asText()));
        SecurityScheme apiKey = null;
        for (Contract contract : versions.values()) {
            for (SecurityScheme scheme : new TreeMap<>(contract.securitySchemes()).values()) {
                String kind = switch (scheme.type() == null ? "" : scheme.type()) {
                    case "http" -> "basic".equalsIgnoreCase(scheme.scheme()) ? "basic"
                            : "bearer".equalsIgnoreCase(scheme.scheme()) ? "bearer" : null;
                    case "basic" -> "basic";
                    case "apiKey" -> "apiKey";
                    case "oauth2" -> scheme.tokenUrl() != null ? "oauth2ClientCredentials" : null;
                    case "mutualTLS" -> "mtls";
                    default -> null;
                };
                if (kind != null && !declared.has("supported")) {
                    supported.add(kind);
                }
                if (kind != null && kind.equals("apiKey") && apiKey == null) {
                    apiKey = scheme;
                }
            }
        }
        ArrayNode supportedNode = NODES.arrayNode();
        supported.forEach(supportedNode::add);
        auth.set("supported", supportedNode);
        if (declared.has("identity")) {
            auth.put("identity", declared.get("identity").asText());
        }
        if (declared.has("unsatisfiedTools")) {
            auth.put("unsatisfiedTools", declared.get("unsatisfiedTools").asText());
        }
        if (supported.contains("apiKey") && apiKey != null && apiKey.in() != null && apiKey.parameterName() != null) {
            auth.set("apiKey", NODES.objectNode().put("in", apiKey.in()).put("name", apiKey.parameterName()));
        }
        node.set("downstreamAuth", auth);

        ObjectNode requestBody = NODES.objectNode().put("multiple", api.path("requestBody").path("multiple").asText("split"));
        if (api.path("requestBody").has("prefer")) {
            requestBody.set("prefer", api.path("requestBody").get("prefer").deepCopy());
        }
        ObjectNode suffixes = NODES.objectNode();
        Map<String, String> merged = new TreeMap<>(DEFAULT_SUFFIXES);
        api.path("requestBody").path("suffixes").fields().forEachRemaining(f -> merged.put(f.getKey(), f.getValue().asText()));
        merged.forEach(suffixes::put);
        requestBody.set("suffixes", suffixes);
        node.set("requestBody", requestBody);
        node.set("pagination", api.has("pagination") ? api.get("pagination").deepCopy() : NODES.nullNode());
        return node;
    }

    private static ObjectNode policies(JsonNode doc, List<String> fileForms) {
        JsonNode safety = doc.path("safety");
        JsonNode defaults = doc.path("runtimeDefaults");
        ObjectNode policies = NODES.objectNode()
                .put("confirmDestructive", safety.path("confirmDestructive").asBoolean(true))
                .put("responseLimitKb", safety.path("responseLimitKb").asInt(256));
        ArrayNode forms = NODES.arrayNode();
        fileForms.forEach(forms::add);
        policies.set("files", NODES.objectNode().set("forms", forms));
        policies.set("redaction", defaults.has("redaction") ? defaults.get("redaction").deepCopy()
                : NODES.objectNode().set("fields", NODES.arrayNode()));
        policies.set("baseUrlAllowlist", defaults.has("baseUrlAllowlist") ? defaults.get("baseUrlAllowlist").deepCopy() : NODES.arrayNode());
        ObjectNode runtimeDefaults = NODES.objectNode();
        for (String key : List.of("timeouts", "audit", "telemetry")) {
            if (defaults.has(key)) {
                runtimeDefaults.set(key, defaults.get(key).deepCopy());
            }
        }
        policies.set("runtimeDefaults", runtimeDefaults);
        return policies;
    }

    /** @param specFile the file that {@code spec} provenance steps without a file refer to */
    private static ObjectNode apiTool(ToolPlan plan, ToolShaper.Shaped shaped, String specFile) {
        ObjectNode tool = NODES.objectNode().put("name", plan.name()).put("kind", "api");
        if (plan.title() != null) {
            tool.put("title", plan.title());
        }
        tool.put("description", plan.description() == null ? "" : plan.description());
        tool.set("annotations", NODES.objectNode()
                .put("readOnlyHint", plan.annotations().readOnlyHint())
                .put("destructiveHint", plan.annotations().destructiveHint())
                .put("idempotentHint", plan.annotations().idempotentHint())
                .put("openWorldHint", plan.annotations().openWorldHint()));
        tool.put("sensitive", plan.sensitive());
        tool.put("confirm", plan.confirm());
        tool.set("inputSchema", shaped.inputSchema());
        tool.set("outputSchema", NODES.nullNode());
        tool.put("api", plan.api());
        ObjectNode operation = NODES.objectNode().put("key", plan.operationKey());
        if (plan.operationId() != null) {
            operation.put("operationId", plan.operationId());
        }
        ArrayNode tags = NODES.arrayNode();
        plan.tags().forEach(tags::add);
        operation.set("tags", tags);
        tool.set("operation", operation);
        tool.set("variants", shaped.variants());
        ObjectNode provenance = NODES.objectNode();
        new TreeMap<>(plan.provenance()).forEach((field, steps) -> {
            ArrayNode list = NODES.arrayNode();
            for (Provenance p : steps) {
                ObjectNode step = NODES.objectNode().put("step", p.step());
                putIfSet(step, "value", p.value());
                putIfSet(step, "file", p.file() == null && p.step().equals("spec") ? specFile : p.file());
                putIfSet(step, "pointer", p.pointer());
                putIfSet(step, "op", p.op());
                putIfSet(step, "since", p.since());
                list.add(step);
            }
            provenance.set(field, list);
        });
        tool.set("provenance", provenance);
        return tool;
    }

    private static ArrayNode diagnosticNodes(List<Diagnostic> earlier) {
        ArrayNode result = NODES.arrayNode();
        for (Diagnostic d : earlier) {
            if (d.level() == Diagnostic.Level.ERROR) {
                continue;
            }
            ObjectNode node = NODES.objectNode()
                    .put("level", d.level().name().toLowerCase())
                    .put("code", d.code())
                    .put("message", d.message());
            int excluded = d.message().indexOf(" excluded: ");
            if (d.code().equals(Codes.OPERATION_EXCLUDED) && excluded > 0) {
                node.put("operation", d.message().substring(0, excluded));
                node.put("reason", d.message().substring(excluded + " excluded: ".length()));
            }
            if (d.location() != null) {
                ObjectNode location = NODES.objectNode().put("file", d.location().file());
                if (d.location().line() != null) {
                    location.put("line", d.location().line());
                }
                if (d.location().pointer() != null && !d.location().pointer().isEmpty()) {
                    location.put("pointer", d.location().pointer());
                }
                node.set("location", location);
            }
            result.add(node);
        }
        return result;
    }

    /** The server version: explicit, or the default contract's version as SemVer, plus the build number. */
    public static String version(JsonNode doc, Build build) {
        if (build.version() != null) {
            return build.version();
        }
        JsonNode first = doc.path("apis").path(0);
        String contract = defaultVersion(first);
        String semver = semver(contract == null ? "0" : contract);
        return build.buildNumber() == null ? semver : semver + "+" + build.buildNumber();
    }

    static String semver(String version) {
        String v = version.startsWith("v") || version.startsWith("V") ? version.substring(1) : version;
        if (v.matches("\\d+(\\.\\d+){0,2}")) {
            String[] parts = v.split("\\.");
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                out.append(i == 0 ? "" : ".").append(i < parts.length ? Integer.parseInt(parts[i]) : 0);
            }
            return out.toString();
        }
        return v;
    }

    // ---------------------------------------------------------------------- helpers

    /** The contract marked {@code default}, else the last one (as the planner decides). */
    private static String defaultVersion(JsonNode api) {
        String result = null;
        for (JsonNode c : api.path("contracts")) {
            if (c.path("default").asBoolean(false)) {
                return c.path("version").asText();
            }
            result = c.path("version").asText();
        }
        return result;
    }

    private static String prefix(JsonNode api) {
        return api.path("toolPrefix").asText(api.path("id").asText());
    }

    private void checkUnique(List<ObjectNode> tools, Location root) {
        for (int i = 1; i < tools.size(); i++) {
            String name = tools.get(i).path("name").asText();
            if (name.equals(tools.get(i - 1).path("name").asText())) {
                diagnostics.error(Codes.NAME_COLLISION, "Two tools are named '" + name + "' (a built-in or pack tool clashes with another tool)", root);
            }
        }
    }

    private static ObjectNode named(String name, ObjectNode tool) {
        ObjectNode result = NODES.objectNode().put("name", name);
        result.setAll(tool.deepCopy());
        return result;
    }

    private static ObjectNode allowed(List<String> values, String defaultValue) {
        ArrayNode allowed = NODES.arrayNode();
        values.forEach(allowed::add);
        return NODES.objectNode().<ObjectNode>set("allowed", allowed).put("default", defaultValue);
    }

    /** A digest over several files: SHA256SUMS-style lines ({@code <hex>  <relative path>}), sorted by path. */
    private static String filesDigest(ResolvedDefinition definition, List<Path> files) {
        Map<String, String> sums = new TreeMap<>();
        for (Path file : files) {
            sums.put(relative(definition, file), CanonicalJson.digest(read(file)).substring("sha256:".length()));
        }
        StringBuilder text = new StringBuilder();
        sums.forEach((path, hex) -> text.append(hex).append("  ").append(path).append('\n'));
        return CanonicalJson.digest(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String relative(ResolvedDefinition definition, Path file) {
        Path root = definition.root().toAbsolutePath().normalize();
        Path abs = file.toAbsolutePath().normalize();
        return (abs.startsWith(root) ? root.relativize(abs) : abs.getFileName()).toString().replace('\\', '/');
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void putIfSet(ObjectNode node, String key, String value) {
        if (value != null) {
            node.put(key, value);
        }
    }
}
