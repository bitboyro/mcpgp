package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.bitboy.mcpgp.runtime.call.ApiToolHandler;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.MDC;
import ro.bitboy.mcpgp.runtime.audit.Arguments;
import ro.bitboy.mcpgp.runtime.audit.AuditLog;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds the MCP server for a manifest and runtime config on a given transport. */
public final class McpServerFactory {

    private static final Logger LOG = LoggerFactory.getLogger(McpServerFactory.class);

    private final Manifest manifest;
    private final RuntimeConfig config;
    private final ToolCatalog catalog;
    private final Map<String, ToolHandler> builtins = new HashMap<>();
    private final ToolHandler apiTools;
    private final AuditLog audit;
    private final Confirmations confirmations;
    private final Clock clock;

    public McpServerFactory(Manifest manifest, RuntimeConfig config) {
        this(manifest, config, new AuditLog(config.audit(), manifest.serverName(), manifest.serverVersion(), Clock.systemUTC()), Clock.systemUTC());
    }

    public McpServerFactory(Manifest manifest, RuntimeConfig config, AuditLog audit, Clock clock) {
        this.manifest = manifest;
        this.config = config;
        this.audit = audit;
        this.clock = clock;
        this.confirmations = new Confirmations(clock);
        this.catalog = new ToolCatalog(manifest, config);
        builtins.put("instances_list", new InstancesList(manifest, config));
        this.apiTools = new ApiToolHandler(manifest, config);
    }

    public ToolCatalog catalog() {
        return catalog;
    }

    public McpSyncServer create(McpServerTransportProvider transport) {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        for (JsonNode tool : catalog.listed()) {
            specs.add(new McpServerFeatures.SyncToolSpecification(ToolCatalog.toMcp(tool),
                    (exchange, request) -> call(exchange, request)));
        }
        var builder = McpServer.sync(transport)
                .serverInfo(new McpSchema.Implementation(manifest.serverName(), manifest.serverTitle(), manifest.serverVersion()))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).logging().build())
                .requestTimeout(Duration.ofSeconds(maxTimeout() + 30L))
                .validateToolInputs(false)       // validated per instance contract by the runtime
                .tools(specs);
        manifest.instructions().ifPresent(builder::instructions);
        return builder.build();
    }

    public AuditLog audit() {
        return audit;
    }

    /**
     * Dispatches a call by tool kind, after confirmation when the tool needs it; every failure becomes
     * an {@code isError} result, and every call is audited.
     */
    public McpSchema.CallToolResult call(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        String name = request.name();
        Map<String, Object> arguments = new LinkedHashMap<>(request.arguments() == null ? Map.of() : request.arguments());
        JsonNode tool = catalog.tool(name).orElse(null);
        CallRecord record = new CallRecord();
        String cid = Ids.correlation();
        long started = System.nanoTime();
        MDC.put("cid", cid);
        McpSchema.CallToolResult result;
        String outcome = "ok";
        String code = null;
        try {
            if (tool == null) {
                throw new ToolError("INPUT_VALIDATION", "Unknown tool " + name);
            }
            ToolHandler handler = handler(tool);
            Object token = arguments.remove("confirm");
            if (tool.path("confirm").asBoolean(false)) {
                confirmations.check(exchange, tool, arguments, token == null ? null : token.toString(),
                        state -> auditConfirm(tool, arguments, state, cid, exchange));
            } else if (token != null) {
                arguments.put("confirm", token);       // not a confirmation tool: let validation reject it
            }
            result = Results.ok(handler.call(exchange, tool, arguments, record), null);
        } catch (ToolError e) {
            outcome = e.code().equals("CONFIRMATION_REQUIRED") || e.code().equals("NOT_AVAILABLE_ON_INSTANCE") ? "denied" : "error";
            code = e.code();
            result = Results.error(e, e.meta());
        } catch (RuntimeException e) {
            LOG.error("Tool {} failed", name, e);
            outcome = "error";
            code = "INTERNAL";
            result = Results.error(new ToolError("INTERNAL", "The tool failed unexpectedly; see the server log"), null);
        } finally {
            MDC.remove("cid");
        }
        result = withCorrelation(result, cid);
        auditCall(name, tool, arguments, record, outcome, code, cid, (System.nanoTime() - started) / 1_000_000, exchange);
        return result;
    }

    private void auditCall(String name, JsonNode tool, Map<String, Object> arguments, CallRecord record, String outcome,
                           String code, String cid, long latencyMs, McpSyncServerExchange exchange) {
        ObjectNode event = audit.event("tool.call", outcome, cid);
        actor(event, exchange);
        event.put("tool", name);
        putIfSet(event, "api", record.api != null ? record.api : tool == null ? null : tool.path("api").asText(null));
        putIfSet(event, "instance", record.instance);
        putIfSet(event, "contract", record.contract);
        putIfSet(event, "code", code);
        if (record.method != null) {
            ObjectNode upstream = event.putObject("upstream").put("method", record.method).put("path", record.path);
            if (record.status == null) {
                upstream.putNull("status");
            } else {
                upstream.put("status", record.status);
            }
            putIfSet(upstream, "body", record.errorBody);
        }
        event.put("latencyMs", latencyMs);
        boolean sensitive = tool != null && tool.path("sensitive").asBoolean(false);
        event.set("args", Arguments.render(arguments, sensitive && audit.argumentsMode().equals("hashed") ? "redacted" : audit.argumentsMode()));
        if (!record.files.isEmpty()) {
            var files = event.putArray("files");
            record.files.forEach(f -> files.addObject().put("name", f.name()).put("sizeBytes", f.sizeBytes()).put("sha256", f.sha256()));
        }
        audit.write(event);
    }

    private void auditConfirm(JsonNode tool, Map<String, Object> arguments, String state, String cid, McpSyncServerExchange exchange) {
        String outcome = state.equals("given") || state.equals("asked") ? "ok" : "denied";
        ObjectNode event = audit.event("tool.confirm", outcome, cid);
        actor(event, exchange);
        event.put("tool", tool.path("name").asText());
        putIfSet(event, "api", tool.path("api").asText(null));
        Object instance = arguments.get("instance");
        putIfSet(event, "instance", instance == null ? null : instance.toString());
        if (!outcome.equals("ok")) {
            event.put("code", "CONFIRMATION_REQUIRED");
        }
        event.put("confirm", state);
        audit.write(event);
    }

    private static void actor(ObjectNode event, McpSyncServerExchange exchange) {
        if (exchange != null && exchange.getClientInfo() != null) {
            event.putObject("actor").put("kind", "client").put("id", exchange.getClientInfo().name());
        }
    }

    /** Every result carries the correlation id that its audit event has (tools-contract §6). */
    private static McpSchema.CallToolResult withCorrelation(McpSchema.CallToolResult result, String cid) {
        Map<String, Object> meta = new LinkedHashMap<>(result.meta() == null ? Map.of() : result.meta());
        meta.put("mcpgp/correlationId", cid);
        return new McpSchema.CallToolResult(result.content(), result.isError(), result.structuredContent(), meta);
    }

    private static void putIfSet(ObjectNode node, String key, String value) {
        if (value != null) {
            node.put(key, value);
        }
    }

    private ToolHandler handler(JsonNode tool) {
        String kind = tool.path("kind").asText();
        return switch (kind) {
            case "api" -> apiTools;
            case "builtin" -> {
                ToolHandler handler = builtins.get(tool.path("handler").asText());
                if (handler == null) {
                    throw new ToolError("INTERNAL", tool.path("name").asText() + " is not available in this runtime version");
                }
                yield handler;
            }
            default -> throw new ToolError("INTERNAL", tool.path("name").asText() + " is not available in this runtime version");
        };
    }

    private int maxTimeout() {
        return config.allInstances().stream().mapToInt(RuntimeConfig.Instance::timeoutSeconds).max().orElse(30);
    }
}
