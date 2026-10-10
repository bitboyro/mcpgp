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
        Redactor redactor = new Redactor(config.secrets());
        this.upstream = new Upstream(apiKeys, redactor);
        this.responses = new ResponseMapper(manifest.policies().path("responseLimitKb").asInt(256),
                config.errorDetail(), redactor);
    }

    @Override
    public Object call(McpSyncServerExchange exchange, JsonNode tool, Map<String, Object> arguments, CallRecord record) {
        Manifest.Api api = manifest.apis().get(tool.path("api").asText());
        record.api = api.id();
        RuntimeConfig.Instance instance = instance(api, arguments);
        String contract = instance.contract().equals("auto") ? api.defaultContract() : instance.contract();
        record.instance = instance.id();
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
            RequestBuilder.UpstreamRequest request = requests.build(variant, args, base, record);
            LOG.debug("{} {} → {}", request.method(), variant.path("path").asText(), instance.id());
            // security: [] marks a public operation: no credentials (auth.md §2.2)
            boolean publicOperation = variant.path("security").isArray() && variant.path("security").isEmpty();
            HttpResponse<byte[]> response = upstream.send(instance, request, publicOperation);
            record.status = response.statusCode();
            return responses.map(response, instance.id(), meta, record);
        } catch (ToolError e) {
            throw e.meta().isEmpty() ? e.withMeta(meta) : e;
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
