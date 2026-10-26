package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.bitboy.mcpgp.runtime.config.BaseUrl;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.mcp.CallRecord;
import ro.bitboy.mcpgp.runtime.mcp.ToolError;
import ro.bitboy.mcpgp.runtime.mcp.ToolHandler;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs an API tool: picks the instance and its contract, checks access and input, builds the request
 * for that contract's variant, sends it and maps the response.
 */
public final class ApiToolHandler implements ToolHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiToolHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ro.bitboy.mcpgp.runtime.mcp.RetainedResults retained;
    private final Manifest manifest;
    private final RuntimeConfig config;
    private final ro.bitboy.mcpgp.runtime.config.ToolPolicy policy;
    private final InputValidator validator = new InputValidator();
    private final RequestBuilder requests;
    private final Upstream upstream;
    private final Redactor redactor;
    private Enrichment enrichment = Enrichment.NONE;
    /** The recording pack, or null when the build doesn't have it. */
    private final Recordings recordings;
    private final ResponseMapper responses;
    private final Contracts contracts;
    private final RateLimits rateLimits;

    public ApiToolHandler(Manifest manifest, RuntimeConfig config) {
        this.manifest = manifest;
        this.retained = new ro.bitboy.mcpgp.runtime.mcp.RetainedResults(manifest.policies(), java.time.Clock.systemUTC());
        this.config = config;
        this.policy = new ro.bitboy.mcpgp.runtime.config.ToolPolicy(manifest, config);
        this.requests = new RequestBuilder(new FileInputs(config.files()));
        Map<String, JsonNode> apiKeys = new LinkedHashMap<>();
        manifest.apis().forEach((id, api) -> {
            JsonNode placement = api.upstreamAuth().path("apiKey");
            if (placement.isObject()) {
                apiKeys.put(id, placement);
            }
        });
        this.redactor = new Redactor(config.secrets());
        int responseLimit = manifest.policies().path("responseLimitKb").asInt(256) * 1024;
        if (retained.enabled()) responseLimit = manifest.policies().at("/results/captureLimitKb").asInt(4096) * 1024;
        // a little more than the limit is read, so a JSON answer can be cut at whole items; never more than this
        this.upstream = new Upstream(apiKeys, redactor, retained.enabled() ? responseLimit : Math.max(4 * responseLimit, 1024 * 1024));
        this.contracts = new Contracts(manifest, config.allInstances(), upstream, java.time.Clock.systemUTC());
        this.rateLimits = new RateLimits(manifest, java.time.Clock.systemUTC());
        this.recordings = recordings(manifest, config, apiKeys);
        this.responses = new ResponseMapper(responseLimit / 1024,
                config.errorDetail(), redactor, manifest.policies().path("cleanTruncation").asBoolean(true),
                manifest.policies().at("/rateLimits/retryAfter").asBoolean(true), MaskedFields.of(manifest));
    }

    public ro.bitboy.mcpgp.runtime.mcp.RetainedResults resultStore() { return retained; }

    /** Preflight performs no operation request and consumes no confirmation token. */
    public void preflight(McpSyncServerExchange exchange, JsonNode tool, Map<String, Object> arguments) {
        Map<String, Object> routed = new LinkedHashMap<>(arguments);
        if (tool.has("x-mcpgp-instance")) routed.put("instance", tool.path("x-mcpgp-instance").asText());
        Object sent = exchange == null || exchange.transportContext() == null ? null : exchange.transportContext().get(ro.bitboy.mcpgp.runtime.mcp.McpServerFactory.CREDENTIALS_KEY);
        Map<String, String> credentials = new java.util.HashMap<>();
        if (sent instanceof Map<?, ?> map) map.forEach((k, v) -> credentials.put(k.toString(), v.toString()));
        Object subject = exchange == null || exchange.transportContext() == null ? null : exchange.transportContext().get(ro.bitboy.mcpgp.runtime.mcp.McpServerFactory.SUBJECT_TOKEN_KEY);
        if (subject instanceof String token) credentials.put(Upstream.SUBJECT, token);
        Manifest.Api api = manifest.apis().get(tool.path("api").asText());
        RuntimeConfig.Instance instance = instance(api, routed);
        Contracts.Choice choice = contracts.forCall(instance, credentials);
        if (!choice.available()) throw new ToolError("INSTANCE_UNAVAILABLE", instance.id() + " is unavailable");
        String contract = choice.contract();
        JsonNode variant = tool.path("variants").get(contract);
        if (variant == null || instance.readOnly() && !tool.at("/annotations/readOnlyHint").asBoolean(false))
            throw new ToolError("NOT_AVAILABLE_ON_INSTANCE", "Operation is unavailable on " + instance.id());
        boolean publicOperation = variant.path("security").isArray() && variant.path("security").isEmpty();
        Upstream.credential(instance, credentials, publicOperation, variant.get("security"), api.upstreamAuth().path("schemes"));
        validator.validate(tool, JSON.valueToTree(arguments), contract, instance.id());
        requests.build(variant, JSON.valueToTree(arguments), BaseUrl.resolve(instance.baseUrl(), api.contracts().get(contract).basePath()), new CallRecord());
    }

    /** Which contract each instance uses (runtime-config §4.4). */
    public Contracts contracts() {
        return contracts;
    }

    /** The first or the last non-empty line of a text (a truncation note is the last), at most 200 characters. */
    private static String line(String text, boolean last) {
        java.util.stream.Stream<String> lines = text == null ? java.util.stream.Stream.empty() : text.lines().filter(l -> !l.isBlank());
        String line = (last ? lines.reduce((a, b) -> b) : lines.findFirst()).orElse("");
        return line.length() <= 200 ? line : line.substring(0, 199) + "…";
    }

    /** The request enrichers to run before each call is sent (extensions.md §3). */
    public void enrichment(Enrichment enrichment) {
        this.enrichment = enrichment;
    }

    /** Replaces the secrets this server knows in a text (for extension logs). */
    public String redact(String text) {
        return redactor.redact(text);
    }

    @Override
    public Object call(McpSyncServerExchange exchange, JsonNode tool, Map<String, Object> arguments, CallRecord record) {
        Object sent = exchange == null || exchange.transportContext() == null ? null
                : exchange.transportContext().get(ro.bitboy.mcpgp.runtime.mcp.McpServerFactory.CREDENTIALS_KEY);
        @SuppressWarnings("unchecked")
        Map<String, String> credentials = new java.util.HashMap<>(sent instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of());
        Object subject = exchange == null || exchange.transportContext() == null ? null
                : exchange.transportContext().get(ro.bitboy.mcpgp.runtime.mcp.McpServerFactory.SUBJECT_TOKEN_KEY);
        if (subject instanceof String token) {
            credentials.put(Upstream.SUBJECT, token); // the signed-in user's token, for tokenExchange (auth.md §2.5)
        }
        // whatever the client sent is masked in everything this call writes: results, errors, recordings
        try (AutoCloseable masked = Redactor.during(List.copyOf(credentials.values()))) {
            return call(exchange, tool, arguments, record, credentials);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Object call(McpSyncServerExchange exchange, JsonNode tool, Map<String, Object> arguments, CallRecord record,
                        Map<String, String> credentials) {
        Manifest.Api api = manifest.apis().get(tool.path("api").asText());
        record.api = api.id();
        RuntimeConfig.Instance instance = instance(api, arguments);
        record.instance = instance.id();
        ro.bitboy.mcpgp.runtime.mcp.CallerAccess.require(config, manifest, tool, instance);
        policy.deniedBy(tool, instance).ifPresent(source -> {
            throw new ToolError("NOT_AVAILABLE_ON_INSTANCE", "Tool " + tool.path("name").asText()
                    + " is blocked on " + instance.id() + " by " + source);
        });
        Contracts.Choice choice = contracts.forCall(instance, credentials);
        if (!choice.available()) {
            throw new ToolError("INSTANCE_UNAVAILABLE", instance.id() + " is unavailable: " + (choice.selection().equals("detected") ? choice.reason()
                            : "its contract version isn't known (" + choice.reason() + ")"),
                    choice.selection().equals("detected") ? "Use another instance; whoever runs this server can pin it or change the build"
                            : "It's asked again within a minute; use another instance, or ask whoever runs this server to check it (doctor)")
                    .withMeta(Map.<String, Object>of("mcpgp/instance", instance.id()));
        }
        String contract = choice.contract();
        record.contract = contract;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("mcpgp/instance", instance.id());
        meta.put("mcpgp/contract", contract);
        try {
            JsonNode variant = tool.path("variants").get(contract);
            if (variant == null) {
                throw new ToolError("NOT_AVAILABLE_ON_INSTANCE", tool.path("name").asText() + " is not available on "
                        + instance.id() + " (contract " + contract + ")",
                        "It exists in contract " + String.join(", ", fieldNames(tool.path("variants"))));
            }
            if (instance.readOnly() && !tool.at("/annotations/readOnlyHint").asBoolean(false)) {
                throw new ToolError("NOT_AVAILABLE_ON_INSTANCE", instance.id() + " is read-only; "
                        + tool.path("name").asText() + " changes data", "Use a read-only tool, or another instance");
            }
            JsonNode args = JSON.valueToTree(arguments);
            validator.validate(tool, args, contract, instance.id());
            URI base = BaseUrl.resolve(instance.baseUrl(), api.contracts().get(contract).basePath());
            record.method = variant.path("method").asText();
            record.path = variant.path("path").asText();
            RequestBuilder.UpstreamRequest request = enrichment.apply(exchange, tool, record, requests.build(variant, args, base, record));
            LOG.debug("{} {} → {}", request.method(), variant.path("path").asText(), instance.id());
            // security: [] marks a public operation: no credentials (auth.md §2.2)
            boolean publicOperation = variant.path("security").isArray() && variant.path("security").isEmpty();
            Upstream.Credential credential = Upstream.credential(instance, credentials, publicOperation, variant.get("security"),
                    api.upstreamAuth().path("schemes"));
            record.auth = credential.type().equals("none") ? null : credential.type();
            HttpResponse<byte[]> response;
            Recordings.RuntimeConfig_Instance recorded = new Recordings.RuntimeConfig_Instance(instance.api(), instance.id());
            try (AutoCloseable slot = rateLimits.enter(instance)) {
                response = upstream.send(instance, request, credential, publicOperation);
            } catch (ToolError e) {
                if (recordings != null && !e.code().equals("RATE_LIMITED")) {
                    recordings.capture(tool.path("name").asText(), recorded, request, null, e.getMessage());
                }
                throw e;
            } catch (RuntimeException e) {
                if (recordings != null) {
                    recordings.capture(tool.path("name").asText(), recorded, request, null, e.getMessage());
                }
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e); // releasing a slot doesn't throw
            }
            if (recordings != null) {
                recordings.capture(tool.path("name").asText(), recorded, request, response, null);
            }
            record.status = response.statusCode();
            ro.bitboy.mcpgp.runtime.mcp.ToolOutput output = responses.map(response, instance.id(), meta, record);
            // a tool that declares an outputSchema answers with structured content, or says why it can't (MCP tools/call)
            if (output.structured() == null && tool.path("outputSchema").isObject()) {
                boolean cut = meta.containsKey("mcpgp/truncated");
                throw new ToolError(cut ? "RESPONSE_TOO_LARGE" : "RESPONSE_PARSE_ERROR", cut
                        ? "The answer is over the size limit and couldn't be cut at whole items: " + line(output.text(), true)
                        : "The target answered without the JSON this tool's output schema describes",
                        cut ? "Narrow the query (filters, a smaller page)" : "The answer starts: " + line(output.text(), false)).withMeta(meta);
            }
            return retained.format(exchange, output);
        } catch (ToolError e) {
            if (!tool.at("/annotations/readOnlyHint").asBoolean(false) && record.status != null && record.status >= 200 && record.status < 300) {
                e = new ToolError(e.code(), e.getMessage(), "The target returned HTTP " + record.status
                        + ". The operation was accepted; do not repeat it to recover output. " + (e.hint() == null ? "" : e.hint()))
                        .withDetails(e.details()).withMeta(e.meta());
            }
            if (e.meta().isEmpty()) {
                throw e.withMeta(meta);
            }
            Map<String, Object> merged = new LinkedHashMap<>(meta);
            merged.putAll(e.meta());          // e.g. how long to wait
            throw e.withMeta(merged);
        }
    }

    /** The recording pack's store, when the build has the pack (packaging.md §4.1). */
    static Recordings recordings(Manifest manifest, RuntimeConfig config, Map<String, JsonNode> apiKeys) {
        boolean pack = false;
        for (JsonNode p : manifest.root().path("packs")) {
            pack |= p.path("id").asText().equals("recording");
        }
        if (!pack) {
            return null;
        }
        java.util.Set<String> fields = new java.util.HashSet<>(MaskedFields.of(manifest).names());
        java.util.Set<String> keyHeaders = new java.util.HashSet<>();
        apiKeys.values().forEach(placement -> {
            if (placement.path("in").asText("header").equals("header")) {
                keyHeaders.add(placement.path("name").asText());
            } else {
                fields.add(placement.path("name").asText().toLowerCase(java.util.Locale.ROOT));   // an API key in the query is masked there
            }
        });
        return Recordings.of(config.storageRoot(), fields, keyHeaders, config.secrets(),
                manifest.policies().path("responseLimitKb").asInt(256) * 1024, config.packs().path("recording").path("retainDays").asInt(14));
    }

    private RuntimeConfig.Instance instance(Manifest.Api api, Map<String, Object> arguments) {
        List<RuntimeConfig.Instance> instances = config.instances().getOrDefault(api.id(), List.of());
        if (!manifest.multiInstance()) {
            return instances.get(0);
        }
        Object requested = arguments.get("instance");
        List<String> names = instances.stream().map(RuntimeConfig.Instance::id).toList();
        if (requested == null || requested.toString().isBlank()) {
            throw new ToolError("INSTANCE_REQUIRED", "instance is required", "One of: " + String.join(", ", names));
        }
        return instances.stream().filter(i -> i.id().equals(requested.toString())).findFirst()
                .orElseThrow(() -> new ToolError("INSTANCE_UNKNOWN", "No instance named '" + requested + "'",
                        "One of: " + String.join(", ", names)));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
