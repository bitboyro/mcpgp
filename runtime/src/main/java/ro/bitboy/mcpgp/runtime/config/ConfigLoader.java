package ro.bitboy.mcpgp.runtime.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.schemas.SchemaKind;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loads the operator's config (runtime-config.md): build defaults, then the config file (YAML or JSON),
 * then environment variables (including {@code .env} next to the config), then CLI flags; validates it,
 * resolves secret references and checks it against what the build allows (§7). Every problem is
 * collected and reported together.
 */
public final class ConfigLoader {

    /**
     * @param configFile an explicit {@code --config} file
     * @param workingDir where default config file names and {@code .env} are looked for
     * @param flags      {@code --a.b.c value} flags, by dotted key
     */
    public record Options(Optional<Path> configFile, Path workingDir, Map<String, String> env, Map<String, String> flags) {
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "[::1]", "localhost");

    private final Schemas schemas;
    private final SchemaNav nav;

    public ConfigLoader(Schemas schemas) {
        this.schemas = schemas;
        try (var in = Schemas.class.getResourceAsStream("/schemas/" + SchemaKind.RUNTIME_CONFIG.fileName())) {
            this.nav = new SchemaNav(JSON.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("runtime-config schema missing", e);
        }
    }

    public RuntimeConfig load(Manifest manifest, Options options) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String name = manifest.serverName();

        // 1. the file
        Optional<Path> file = locate(name, options, problems);
        ObjectNode fromFile = JsonNodeFactory.instance.objectNode();
        if (file.isPresent()) {
            fromFile = read(file.get(), problems);
        }
        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        Path baseDir = file.map(f -> f.toAbsolutePath().getParent()).orElse(options.workingDir().toAbsolutePath());

        // 2. environment: real variables win over .env
        Map<String, String> env = new HashMap<>(dotEnv(baseDir.resolve(".env"), problems));
        env.putAll(options.env());

        // 3. build defaults < file < environment < flags
        ObjectNode document = buildDefaults(manifest);
        mergeInto(document, fromFile);
        EnvOverrides overrides = new EnvOverrides(nav);
        EnvOverrides.Result withEnv = overrides.apply(document, env, EnvOverrides.prefix(name));
        problems.addAll(withEnv.problems());
        warnings.addAll(withEnv.warnings());
        // flags use the same mapping as environment variables: --server.port 8080 ~ F_SERVER__PORT=8080
        Map<String, String> flagEnv = new LinkedHashMap<>();
        Map<String, String> flagNames = new HashMap<>();
        options.flags().forEach((key, value) -> {
            // list indexes attach to their list: instances.0.id → INSTANCES_0__ID
            String asEnv = "F_" + key.replaceAll("\\.(\\d+)(?=\\.|$)", "_$1").replace(".", "__").toUpperCase(Locale.ROOT);
            flagEnv.put(asEnv, value);
            flagNames.put(asEnv, key);
        });
        EnvOverrides.Result withFlags = overrides.apply(withEnv.config(), flagEnv, "F_", false);
        withFlags.problems().forEach(p -> problems.add(p.replaceFirst("^F_\\S*", "flag")));
        for (String warning : withFlags.warnings()) {
            problems.add("Unknown flag --" + flagNames.getOrDefault(warning.substring(0, warning.indexOf(' ')), warning));
        }
        document = withFlags.config();

        // 4. schema
        for (Schemas.Problem problem : schemas.problems(SchemaKind.RUNTIME_CONFIG, document)) {
            problems.add(problem.toString());
        }
        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }

        // 5. secrets
        Secrets.Result secrets = new Secrets(nav, env, baseDir).resolve(document, withEnv.fromEnv());
        problems.addAll(secrets.problems());
        boolean allowInline = document.path("allowInlineSecrets").asBoolean(false);
        if (!secrets.inline().isEmpty()) {
            if (allowInline) {
                warnings.add("allowInlineSecrets is on and these secret fields hold plain values: " + String.join(", ", secrets.inline())
                        + "; use references (${env:…}, ${file:…}) outside local experiments");
            } else {
                secrets.inline().forEach(field -> problems.add(field
                        + ": a secret must be a reference (${env:NAME} or ${file:path}) or an environment variable, not a plain value"));
            }
        }
        JsonNode resolved = secrets.config();

        // 6. what the build allows
        RuntimeConfig.Server server = server(manifest, resolved.path("server"), problems);
        Path storage = storageRoot(name, resolved);
        Map<String, List<RuntimeConfig.Instance>> instances = instances(manifest, resolved, baseDir, problems, warnings);
        credentialHeaders(instances, server, problems, warnings);
        RuntimeConfig.Files files = files(manifest, resolved.path("files"), storage, baseDir, problems);
        if (resolved.path("telemetry").path("enabled").asBoolean(false) && resolved.at("/telemetry/exporter").asText("otlp").equals("prometheus")
                && !server.transport().equals("http")) {
            problems.add("/telemetry/exporter: prometheus needs the http transport (a scrape endpoint); use otlp for stdio servers");
        }
        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        JsonNode audit = resolved.path("audit");
        RuntimeConfig.Audit auditConfig = new RuntimeConfig.Audit(
                audit.path("file").path("enabled").asBoolean(true),
                audit.path("file").has("path") ? expand(audit.path("file").path("path").asText(), baseDir) : storage.resolve("audit/audit.jsonl"),
                audit.path("file").path("rotate").asText("daily"),
                audit.path("file").path("retainDays").asInt(30),
                audit.path("arguments").asText("redacted"));
        return new RuntimeConfig(file, resolved, server, instances, storage, auditConfig, files,
                resolved.path("errors").path("detail").asText("full"),
                resolved.path("logging").path("level").asText("info"),
                resolved.path("telemetry"), resolved.path("packs"),
                Set.copyOf(secrets.secrets()), allowInline && !secrets.inline().isEmpty(), List.copyOf(warnings),
                configHash(document, secrets.inline()));
    }

    // ---------------------------------------------------------------------- sources

    private static Optional<Path> locate(String name, Options options, List<String> problems) {
        if (options.configFile().isPresent()) {
            Path file = options.configFile().get();
            if (!Files.isRegularFile(file)) {
                problems.add("Config file " + file + " does not exist");
            }
            return Optional.of(file);
        }
        List<Path> found = new ArrayList<>();
        for (String extension : List.of(".yaml", ".yml", ".json")) {
            Path candidate = options.workingDir().resolve(name + extension);
            if (Files.isRegularFile(candidate)) {
                found.add(candidate);
            }
        }
        if (found.size() > 1) {
            problems.add("Several config files found (" + found.stream().map(p -> p.getFileName().toString()).toList()
                    + "); keep one or choose with --config");
        }
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private static ObjectNode read(Path file, List<String> problems) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        ObjectMapper mapper;
        if (fileName.endsWith(".json")) {
            mapper = JSON;
        } else if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            mapper = YAML;
        } else {
            problems.add("Config file " + file + " must end in .yaml, .yml or .json");
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            JsonNode node = mapper.readTree(file.toFile());
            if (node == null || node.isMissingNode() || node.isNull()) {
                return JsonNodeFactory.instance.objectNode();
            }
            if (!node.isObject()) {
                problems.add("Config file " + file + " must contain an object at the top level");
                return JsonNodeFactory.instance.objectNode();
            }
            return (ObjectNode) node;
        } catch (IOException e) {
            problems.add("Config file " + file + " cannot be parsed: " + firstLine(e.getMessage()));
            return JsonNodeFactory.instance.objectNode();
        }
    }

    /** {@code KEY=value} lines; {@code #} comments; optional {@code export }; quotes around values removed. */
    static Map<String, String> dotEnv(Path file, List<String> problems) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return values;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring("export ".length()).strip();
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    problems.add(file.getFileName() + " line " + (i + 1) + ": expected NAME=value");
                    continue;
                }
                String value = line.substring(eq + 1).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    // double quotes: \" and \\ are escapes
                    value = value.substring(1, value.length() - 1).replaceAll("\\\\([\"\\\\])", "$1");
                } else if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(line.substring(0, eq).strip(), value);
            }
        } catch (IOException e) {
            problems.add("Cannot read " + file);
        }
        return values;
    }

    private static ObjectNode buildDefaults(Manifest manifest) {
        ObjectNode defaults = JsonNodeFactory.instance.objectNode();
        JsonNode runtimeDefaults = manifest.policies().path("runtimeDefaults");
        for (String key : List.of("audit", "telemetry")) {
            if (runtimeDefaults.has(key)) {
                defaults.set(key, runtimeDefaults.get(key).deepCopy());
            }
        }
        return defaults;
    }

    // ---------------------------------------------------------------------- sections

    private static RuntimeConfig.Server server(Manifest manifest, JsonNode server, List<String> problems) {
        String transport = server.path("transport").asText(manifest.defaultOf("transports"));
        if (!manifest.allowed("transports").contains(transport)) {
            problems.add("/server/transport: '" + transport + "' is not available in this build (allowed: " + manifest.allowed("transports") + ")");
        }
        String authMode = server.path("auth").path("mode").asText(manifest.defaultOf("inboundAuth") == null ? "none" : manifest.defaultOf("inboundAuth"));
        List<String> allowedAuth = manifest.allowed("inboundAuth");
        if (!allowedAuth.isEmpty() && !allowedAuth.contains(authMode)) {
            problems.add("/server/auth/mode: '" + authMode + "' is not available in this build (allowed: " + allowedAuth + ")");
        }
        // described in runtime-config, not done yet: refused, so nobody relies on them (runtime-config §3)
        if (server.path("tls").isObject()) {
            problems.add("/server/tls: serving HTTPS itself isn't supported yet; put a reverse proxy in front for TLS and set tls: proxy");
        }
        if (server.has("trustProxy")) {
            problems.add("/server/trustProxy isn't supported yet: the server doesn't read X-Forwarded-* headers; remove it");
        }
        if (server.has("outboundProxy")) {
            problems.add("/server/outboundProxy isn't supported yet: calls to target servers go direct; remove it");
        }
        String host = server.path("host").asText("127.0.0.1");
        boolean allowInsecure = server.path("auth").path("allowInsecure").asBoolean(false);
        if (transport.equals("http") && authMode.equals("none") && !LOOPBACK.contains(host) && !allowInsecure) {
            problems.add("/server/auth/mode: 'none' on a non-loopback address (" + host
                    + ") needs server.auth.allowInsecure: true; prefer static or oauth");
        }
        List<String> origins = new ArrayList<>();
        server.path("allowedOrigins").forEach(o -> origins.add(o.asText()));
        String basePath = server.path("basePath").asText("/mcp");
        // inbound auth applies to HTTP only (auth.md §1)
        if (transport.equals("http") && authMode.equals("oauth")) {
            // tokens must name this server as their audience (RFC 8707), so it needs its public address
            if (server.path("auth").path("issuer").asText("").isEmpty()) {
                problems.add("/server/auth/issuer: oauth mode needs the authorization server (issuer)");
            }
            if (server.path("publicUrl").asText("").isEmpty()) {
                problems.add("/server/publicUrl: oauth mode needs the address clients use for this server (tokens are issued for it)");
            }
        }
        if (transport.equals("http") && authMode.equals("static") && server.path("auth").path("keys").isEmpty()) {
            problems.add("/server/auth/keys: static mode needs at least one key ({ name, value })");
        }
        Integer management = server.path("management").path("port").isInt() ? server.path("management").path("port").asInt() : null;
        int port = server.path("port").asInt(8080);
        if (management != null && management == port) {
            problems.add("/server/management/port: must differ from server.port");
        }
        return new RuntimeConfig.Server(transport, host, port, basePath,
                server.path("publicUrl").asText(null), authMode, server.path("auth"), allowInsecure, List.copyOf(origins), management);
    }

    private static Map<String, List<RuntimeConfig.Instance>> instances(Manifest manifest, JsonNode doc, Path baseDir,
                                                                         List<String> problems, List<String> warnings) {
        boolean multiApi = manifest.apis().size() > 1;
        boolean multi = manifest.multiInstance();
        Map<String, List<RuntimeConfig.Instance>> result = new LinkedHashMap<>();
        if (multiApi && (doc.has("instance") || doc.has("instances"))) {
            problems.add("This server combines several APIs; configure instances per API under apis.<id> ("
                    + String.join(", ", manifest.apis().keySet()) + ")");
        }
        if (!multiApi && doc.has("apis")) {
            problems.add("/apis: only for servers that combine several APIs; use " + (multi ? "instances" : "instance"));
        }
        Set<String> knownApis = manifest.apis().keySet();
        doc.path("apis").fieldNames().forEachRemaining(id -> {
            if (multiApi && !knownApis.contains(id)) {
                problems.add("/apis/" + id + ": unknown API (this server has " + String.join(", ", knownApis) + ")");
            }
        });
        int defaultTimeout = manifest.policies().at("/runtimeDefaults/timeouts/defaultSeconds").asInt(30);
        int maxTimeout = manifest.policies().at("/runtimeDefaults/timeouts/maxSeconds").asInt(Integer.MAX_VALUE);

        for (Manifest.Api api : manifest.apis().values()) {
            JsonNode scope = multiApi ? doc.path("apis").path(api.id()) : doc;
            String at = multiApi ? "/apis/" + api.id() : "";
            List<JsonNode> configured = new ArrayList<>();
            if (multi) {
                if (scope.has("instance")) {
                    problems.add(at + "/instance: this is a multi-instance build; use instances: [ … ] with an id per instance");
                }
                scope.path("instances").forEach(configured::add);
            } else {
                if (scope.has("instances")) {
                    problems.add(at + "/instances: this is a single-instance build; use instance: { … }");
                }
                if (scope.has("instance")) {
                    configured.add(scope.get("instance"));
                }
            }
            if (configured.isEmpty()) {
                problems.add("No target server configured for " + api.title() + ": set " + at.replaceFirst("^/", "").replace('/', '.')
                        + (at.isEmpty() ? "" : ".") + (multi ? "instances" : "instance") + " (see runtime-config.md §3)");
                continue;
            }
            List<RuntimeConfig.Instance> list = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < configured.size(); i++) {
                JsonNode node = configured.get(i);
                String where = at + (multi ? "/instances/" + i : "/instance");
                String id = multi ? node.path("id").asText("") : "default";
                if (multi && id.isEmpty()) {
                    problems.add(where + "/id: every instance needs an id (the value agents pass as `instance`)");
                    continue;
                }
                if (!ids.add(id)) {
                    problems.add(where + "/id: '" + id + "' is used twice");
                }
                String baseUrl = node.path("baseUrl").asText();
                try {
                    if (BaseUrl.parse(baseUrl).getScheme().equalsIgnoreCase("http")) {
                        warnings.add("Instance " + id + " uses plain HTTP (" + baseUrl + ")");
                    }
                } catch (IllegalArgumentException e) {
                    problems.add(where + "/baseUrl: " + e.getMessage());
                }
                String contract = node.path("contract").asText("auto");
                if (!contract.equals("auto") && !api.contracts().containsKey(contract)) {
                    problems.add(where + "/contract: '" + contract + "' is not shipped with this server (shipped: "
                            + String.join(", ", api.contracts().keySet()) + ", or auto)");
                }
                String prefer = node.hasNonNull("prefer") ? node.get("prefer").asText() : null;
                if (prefer != null && !contract.equals("auto")) {
                    problems.add(where + "/prefer: only for contract: auto (a pinned contract is used as it is)");
                } else if (prefer != null && !api.contracts().containsKey(prefer)) {
                    problems.add(where + "/prefer: '" + prefer + "' is not shipped with this server (shipped: "
                            + String.join(", ", api.contracts().keySet()) + ")");
                }
                JsonNode auth = node.has("auth") ? node.get("auth").deepCopy() : JsonNodeFactory.instance.objectNode().put("type", "none");
                List<JsonNode> entries = new ArrayList<>();
                if (auth.isArray()) {
                    auth.forEach(entries::add);
                } else {
                    entries.add(auth);
                }
                JsonNode fromRequest = api.node().path("downstreamAuth").path("fromRequest");
                boolean exchange = api.node().path("downstreamAuth").path("tokenExchange").isObject();
                for (int e = 0; e < entries.size(); e++) {
                    JsonNode entry = entries.get(e);
                    String at2 = where + "/auth" + (auth.isArray() ? "/" + e : "");
                    String authType = entry.path("type").asText("none");
                    if (authType.equals("fromRequest")) {
                        if (!fromRequest.isObject()) {
                            problems.add(at2 + "/type: credentials from the request are not enabled in this server's definition"
                                    + " (downstreamAuth.fromRequest)");
                        } else if (!entry.has("header")) {
                            ((ObjectNode) entry).put("header", fromRequest.has("header") && !multi && manifest.apis().size() == 1
                                    ? fromRequest.get("header").asText()
                                    : defaultHeader(manifest.serverName(), manifest.apis().size() > 1 ? api.id() : null, multi ? id : null));
                        }
                    } else if (authType.equals("tokenExchange")) {
                        if (!exchange) {
                            problems.add(at2 + "/type: calling the API as the signed-in user is not enabled in this server's definition"
                                    + " (downstreamAuth.tokenExchange)");
                        }
                    } else if (!authType.equals("none") && !api.supportedAuth().contains(authType)) {
                        problems.add(at2 + "/type: '" + authType + "' is not supported by this server (supported: "
                                + String.join(", ", api.supportedAuth()) + ")");
                    }
                    if (e < entries.size() - 1 && !authType.equals("fromRequest") && !authType.equals("tokenExchange")) {
                        warnings.add("Instance " + id + ": the credentials listed after " + authType + " are never used (only a"
                                + " fromRequest entry can be skipped, when its header is missing)");
                    }
                }
                int timeout = node.path("timeoutSeconds").asInt(defaultTimeout);
                if (timeout > maxTimeout) {
                    problems.add(where + "/timeoutSeconds: " + timeout + " is above the build's limit of " + maxTimeout);
                }
                JsonNode tls = node.has("tls") ? node.get("tls") : JsonNodeFactory.instance.objectNode();
                if (tls.path("insecureSkipHostnameVerify").asBoolean(false)) {
                    warnings.add("Instance " + id + " skips the TLS hostname check (insecureSkipHostnameVerify)");
                }
                if (tls.has("clientCert") && !api.supportedAuth().contains("mtls")) {
                    problems.add(where + "/tls/clientCert: client certificates (mtls) are not supported by this server (supported: "
                            + String.join(", ", api.supportedAuth()) + ")");
                }
                TlsBuilder.InstanceTls context = null;
                if (!tls.isEmpty()) {
                    try {
                        context = TlsBuilder.build(tls, baseDir, where + "/tls");
                    } catch (IllegalArgumentException e) {
                        problems.add(e.getMessage());
                    }
                }
                list.add(new RuntimeConfig.Instance(api.id(), id, baseUrl, contract, prefer, node.path("readOnly").asBoolean(false),
                        timeout, auth, tls, context, baseDir));
            }
            result.put(api.id(), List.copyOf(list));
        }
        return result;
    }

    /** {@code X-<Server>[-<Api>][-<Instance>]-Authorization}, each part in title case: {@code X-Orders-Mcp-Prod-Authorization}. */
    static String defaultHeader(String server, String api, String instance) {
        StringBuilder name = new StringBuilder("X");
        for (String part : new String[] {server, api, instance}) {
            if (part == null) {
                continue;
            }
            for (String word : part.split("[^A-Za-z0-9]+")) {
                if (!word.isEmpty()) {
                    name.append('-').append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return name.append("-Authorization").toString();
    }

    /** Headers that carry something else: the MCP transport's, HTTP's own, and cookies. */
    private static final Set<String> NOT_CREDENTIAL_HEADERS = Set.of("host", "content-type", "content-length", "connection", "cookie",
            "transfer-encoding", "expect", "upgrade", "accept", "origin", "mcp-session-id", "mcp-protocol-version", "last-event-id",
            "user-agent", "x-forwarded-for", "x-forwarded-proto", "x-forwarded-host", "traceparent", "tracestate");

    /**
     * Credentials from the request (auth.md §2.4): every instance reads its own header (a production
     * credential never reaches test), none of them collides with what HTTP or MCP use, and the server's
     * own sign-in keeps {@code Authorization} when it uses it.
     */
    private static void credentialHeaders(Map<String, List<RuntimeConfig.Instance>> instances, RuntimeConfig.Server server,
                                          List<String> problems, List<String> warnings) {
        Map<String, String> owners = new java.util.HashMap<>();
        for (RuntimeConfig.Instance instance : instances.values().stream().flatMap(List::stream).toList()) {
            boolean onlyFromRequest = true;
            for (JsonNode entry : instance.authEntries()) {
                if (!entry.path("type").asText().equals("fromRequest")) {
                    onlyFromRequest = false;
                    continue;
                }
                String header = entry.path("header").asText();
                String lower = header.toLowerCase(Locale.ROOT);
                String target = entry.path("as").asText("Authorization").toLowerCase(Locale.ROOT);
                if (NOT_CREDENTIAL_HEADERS.contains(lower)) {
                    problems.add("Instance " + instance.id() + ": " + header + " carries something else; choose another header for the credential");
                } else if (lower.equals("authorization") && !server.authMode().equals("none")) {
                    problems.add("Instance " + instance.id() + ": Authorization carries the client's sign-in to this server ("
                            + server.authMode() + "); send the credential for the API in another header");
                }
                if (NOT_CREDENTIAL_HEADERS.contains(target)) {
                    problems.add("Instance " + instance.id() + ": the credential can't be sent to the API as " + entry.path("as").asText());
                }
                String owner = owners.putIfAbsent(lower, instance.api() + "/" + instance.id());
                if (owner != null) {
                    problems.add("Instance " + instance.id() + ": " + header + " is also the credential header of " + owner
                            + "; give each instance its own header");
                }
            }
            boolean exchanges = instance.authEntries().stream().anyMatch(e -> e.path("type").asText().equals("tokenExchange"));
            boolean signedIn = server.transport().equals("http") && server.authMode().equals("oauth");
            if (exchanges && !signedIn) {
                boolean only = instance.authEntries().stream().allMatch(e -> e.path("type").asText().equals("tokenExchange"));
                String why = "Instance " + instance.id() + ": token exchange needs the user's OAuth sign-in to this server"
                        + " (server.transport: http, server.auth.mode: oauth)";
                if (only) {
                    problems.add(why);
                } else {
                    warnings.add(why + "; the other credential is used");
                }
            }
            for (JsonNode entry : instance.authEntries()) {
                if (entry.path("type").asText().equals("tokenExchange") && entry.path("flavor").asText("").equals("entra-obo")
                        && !entry.has("scopes")) {
                    problems.add("Instance " + instance.id() + ": the on-behalf-of flow needs scopes (e.g. api://<app id>/.default)");
                }
            }
            if (!server.transport().equals("http") && instance.authEntries().stream()
                    .anyMatch(e -> e.path("type").asText().equals("fromRequest"))) {
                if (onlyFromRequest) {
                    problems.add("Instance " + instance.id() + ": credentials from the request need the http transport; over stdio,"
                            + " set the credential in the config or the environment");
                } else {
                    warnings.add("Instance " + instance.id() + ": over stdio there are no request headers; the other credential is used");
                }
            }
        }
    }

    private static RuntimeConfig.Files files(Manifest manifest, JsonNode files, Path storage, Path baseDir, List<String> problems) {
        List<String> forms = new ArrayList<>();
        manifest.policies().at("/files/forms").forEach(f -> forms.add(f.asText()));
        boolean base64 = files.path("base64").path("enabled").asBoolean(forms.contains("base64"));
        boolean path = files.path("path").path("enabled").asBoolean(false);
        if (base64 && !forms.contains("base64")) {
            problems.add("/files/base64/enabled: this build doesn't support base64 file inputs");
        }
        if (path && !forms.contains("path")) {
            problems.add("/files/path/enabled: this build doesn't support file inputs by path");
        }
        Path root = files.path("path").has("root") ? expand(files.path("path").path("root").asText(), baseDir) : storage.resolve("uploads");
        return new RuntimeConfig.Files(base64, files.path("base64").path("maxKb").asInt(1024), path, root,
                files.path("path").path("maxKb").asInt(10240));
    }

    private static Path storageRoot(String serverName, JsonNode doc) {
        String configured = doc.path("storage").path("root").asText("");
        Path home = Path.of(System.getProperty("user.home"));
        return configured.isEmpty() ? home.resolve("." + serverName) : expand(configured, home);
    }

    /** {@code ~} → the user's home; relative paths start at {@code base}. */
    private static Path expand(String path, Path base) {
        String home = System.getProperty("user.home");
        if (path.equals("~")) {
            return Path.of(home);
        }
        if (path.startsWith("~/")) {
            return Path.of(home, path.substring(2));
        }
        Path p = Path.of(path);
        return p.isAbsolute() ? p : base.resolve(p).normalize();
    }

    private static void mergeInto(ObjectNode base, ObjectNode overlay) {
        Iterator<Map.Entry<String, JsonNode>> fields = overlay.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            JsonNode existing = base.get(f.getKey());
            if (existing instanceof ObjectNode e && f.getValue() instanceof ObjectNode o) {
                mergeInto(e, o);
            } else {
                base.set(f.getKey(), f.getValue().deepCopy());
            }
        }
    }

    /**
     * The hash of the config before references are resolved. Plain secret values (allowed only with
     * allowInlineSecrets) are masked first, so the hash never depends on a secret.
     */
    private static String configHash(ObjectNode document, List<String> inline) {
        ObjectNode copy = document.deepCopy();
        for (String pointer : inline) {
            int slash = pointer.lastIndexOf('/');
            JsonNode parent = copy.at(pointer.substring(0, slash));
            if (parent instanceof ObjectNode object) {
                object.put(pointer.substring(slash + 1), "***");
            } else if (parent instanceof com.fasterxml.jackson.databind.node.ArrayNode array) {
                array.set(Integer.parseInt(pointer.substring(slash + 1)), JsonNodeFactory.instance.textNode("***"));
            }
        }
        return "sha256:" + TlsBuilder.sha256(sorted(copy).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static JsonNode sorted(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> keys = new ArrayList<>();
            object.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            keys.forEach(k -> result.set(k, sorted(object.get(k))));
            return result;
        }
        return node;
    }

    private static String firstLine(String message) {
        return message == null ? "" : message.lines().findFirst().orElse("");
    }
}
