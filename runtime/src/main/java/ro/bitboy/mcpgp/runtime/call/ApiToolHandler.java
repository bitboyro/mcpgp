package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import ro.bitboy.mcpgp.runtime.config.BaseUrl;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.mcp.ToolError;
import ro.bitboy.mcpgp.runtime.mcp.ToolHandler;

import java.net.URI;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.HexFormat;
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
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Manifest manifest;
    private final RuntimeConfig config;
    private final InputValidator validator = new InputValidator();
    private final RequestBuilder requests;
    private final Upstream upstream;
    private final ResponseMapper responses;

    public ApiToolHandler(Manifest manifest, RuntimeConfig config) {
        this.manifest = manifest;
        this.config = config;
        this.requests = new RequestBuilder(new FileInputs(config.files()));
        Map<String, JsonNode> apiKeys = new LinkedHashMap<>();
        manifest.apis().forEach((id, api) -> {
            JsonNode placement = api.node().path("downstreamAuth").path("apiKey");
            if (placement.isObject()) {
                apiKeys.put(id, placement);
            }
        });
        this.upstream = new Upstream(apiKeys);
        this.responses = new ResponseMapper(manifest.policies().path("responseLimitKb").asInt(256),
                config.errorDetail(), config.secrets());
    }

    @Override
    public Object call(McpSyncServerExchange exchange, JsonNode tool, Map<String, Object> arguments) {
        Manifest.Api api = manifest.apis().get(tool.path("api").asText());
        RuntimeConfig.Instance instance = instance(api, arguments);
        String contract = instance.contract().equals("auto") ? api.defaultContract() : instance.contract();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("mcpgp/instance", instance.id());
        meta.put("mcpgp/contract", contract);
        byte[] id = new byte[3];
        RANDOM.nextBytes(id);
        meta.put("mcpgp/correlationId", "c-" + HexFormat.of().formatHex(id));
        MDC.put("cid", (String) meta.get("mcpgp/correlationId"));
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
            RequestBuilder.UpstreamRequest request = requests.build(variant, args, base);
            LOG.debug("{} {} → {}", request.method(), variant.path("path").asText(), instance.id());
            HttpResponse<byte[]> response = upstream.send(instance, request);
            return responses.map(response, instance.id(), meta);
        } catch (ToolError e) {
            throw e.meta().isEmpty() ? e.withMeta(meta) : e;
        } finally {
            MDC.remove("cid");
        }
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
