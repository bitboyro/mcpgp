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
    /** The handler of every offered tool, by tool name. */
    private final Map<String, ToolHandler> handlers = new HashMap<>();
    private final ToolHandler apiTools;
    private final AuditLog audit;
    private final Confirmations confirmations;
    private final Clock clock;
    /** {@code toolsets} exposure: the toolsets sessions enable (null in other modes). */
    private final Toolsets toolsets;
    /** stdio, toolsets mode: the API tools currently registered on the server. */
    private final java.util.Set<String> shown = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Who handles calls of the registered tools: this factory, or its replacement after a config reload. */
    private volatile java.util.function.Supplier<McpServerFactory> current = () -> this;

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
        builtins.putAll(new Discovery(manifest, config, catalog, this::call).handlers());
        this.toolsets = Toolsets.active(manifest) ? Toolsets.of(manifest) : null;
        if (toolsets != null) {
            builtins.putAll(toolsets.handlers());
        }
        this.apiTools = new ApiToolHandler(manifest, config);
        Packs packs = new Packs(manifest, config, catalog);
        Map<String, ToolHandler> packHandlers = packs.handlers();
        // tools without an implementation in this runtime are not offered (and said so at startup)
        List<String> unsupported = new ArrayList<>();
        for (JsonNode tool : catalog.all().values()) {
            String name = tool.path("name").asText();
            ToolHandler handler = switch (tool.path("kind").asText()) {
                case "api" -> apiTools;
                case "builtin" -> builtins.get(tool.path("handler").asText());
                case "pack" -> packHandlers.entrySet().stream().filter(e -> name.equals(e.getKey()) || name.endsWith("_" + e.getKey()))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
                default -> null;
            };
            if (handler == null) {
                unsupported.add(name);
            } else {
                handlers.put(name, handler);
            }
        }
        if (!unsupported.isEmpty()) {
            LOG.warn("Not offered, because this runtime version doesn't implement them yet: {}", String.join(", ", unsupported));
            catalog.remove(unsupported);
        }
    }

    public ToolCatalog catalog() {
        return catalog;
    }

    /** The toolsets, in {@code toolsets} exposure; empty otherwise. */
    public java.util.Optional<Toolsets> toolsets() {
        return java.util.Optional.ofNullable(toolsets);
    }

    /**
     * stdio, toolsets mode: registers the tools of the session's enabled toolsets and removes the others;
     * the SDK tells the client its tool list changed.
     */
    public void showToolsets(McpSyncServer server, String session) {
        java.util.Set<String> wanted = toolsets.visible(session);
        for (String name : wanted) {
            catalog.tool(name).ifPresent(tool -> {
                if (shown.add(name)) {
                    server.addTool(spec(tool));
                }
            });
        }
        for (String name : java.util.List.copyOf(shown)) {
            if (!wanted.contains(name)) {
                server.removeTool(name);
                shown.remove(name);
            }
        }
    }

    /** Transport-context key with the authenticated client's key name (HTTP, static mode). */
    public static final String CLIENT_KEY = "mcpgp.client";

    /** stdio. */
    public McpSyncServer create(McpServerTransportProvider transport) {
        return configure(McpServer.sync(transport));
    }

    /** Streamable HTTP. */
    public McpSyncServer create(io.modelcontextprotocol.spec.McpStreamableServerTransportProvider transport) {
        return configure(McpServer.sync(transport));
    }

    private McpSyncServer configure(McpServer.SyncSpecification<?> builder) {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        for (JsonNode tool : catalog.listed()) {
            specs.add(spec(tool));
        }
        builder
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

    public RuntimeConfig config() {
        return config;
    }

    /** A registered tool: its definition, and calls handled by whichever factory is current. */
    private McpServerFeatures.SyncToolSpecification spec(JsonNode tool) {
        return new McpServerFeatures.SyncToolSpecification(ToolCatalog.toMcp(tool),
                (exchange, request) -> current.get().call(exchange, request));
    }

    /**
     * After a config reload: {@code next} handles all calls from now on (calls in flight finish on this
     * one), and tools whose definition changed (the {@code instance} enum) are registered again.
     *
     * @return true when the tool list changed (clients were notified by the SDK, once per changed tool)
     */
    public boolean handOver(McpServerFactory next, McpSyncServer server) {
        java.util.function.Supplier<McpServerFactory> target = () -> next;
        this.current = target;
        next.current = target;
        boolean changed = false;
        Map<String, JsonNode> before = new HashMap<>();
        catalog.listed().forEach(t -> before.put(t.path("name").asText(), t));
        for (JsonNode tool : next.catalog.listed()) {
            JsonNode old = before.remove(tool.path("name").asText());
            if (!tool.equals(old)) {
                server.addTool(next.spec(tool));      // replaces the tool of the same name; notifies clients
                changed = true;
            }
        }
        for (String gone : before.keySet()) {
            server.removeTool(gone);
            changed = true;
        }
        return changed;
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
        ro.bitboy.mcpgp.runtime.telemetry.Telemetry.Call traced = ro.bitboy.mcpgp.runtime.telemetry.Telemetry.current()
                .call(name, tool == null ? null : tool.path("api").asText(null));
        try {
            if (tool == null) {
                throw new ToolError("INPUT_VALIDATION", "Unknown tool " + name);
            }
            if (toolsets != null && tool.path("kind").asText().equals("api")) {
                java.util.Optional<List<String>> blocked = toolsets.blocked(name, Toolsets.session(exchange));
                if (blocked.isPresent()) {
                    throw new ToolError("TOOLSET_DISABLED", blocked.get().isEmpty() ? "Tool " + name + " is in no toolset"
                            : "Tool " + name + " is in the toolset " + String.join(" or ", blocked.get()) + ", which is not enabled",
                            "Enable it with toolset_enable, then call the tool");
                }
            }
            ToolHandler handler = handlers.get(name);
            Object token = arguments.remove("confirm");
            if (tool.path("confirm").asBoolean(false)) {
                confirmations.check(exchange, tool, arguments, token == null ? null : token.toString(),
                        state -> auditConfirm(tool, arguments, state, cid, exchange));
            } else if (token != null) {
                arguments.put("confirm", token);       // not a confirmation tool: let validation reject it
            }
            Object value = handler.call(exchange, tool, arguments, record);
            if (value instanceof Discovery.Passthrough passthrough) {
                // call_tool / invoke_operation: the inner call was audited itself; return its result as is
                result = passthrough.result();
                if (Boolean.TRUE.equals(result.isError())) {
                    outcome = "error";
                    code = result.structuredContent() instanceof Map<?, ?> m && m.get("code") != null ? m.get("code").toString() : "INTERNAL";
                }
            } else {
                result = Results.ok(value, null);
            }
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
            traced.result(record.api, record.instance, record.contract, outcome, code);
            traced.close();
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

    private void actor(ObjectNode event, McpSyncServerExchange exchange) {
        if (exchange == null) {
            return;
        }
        Object key = exchange.transportContext() == null ? null : exchange.transportContext().get(CLIENT_KEY);
        if (key != null) {
            event.putObject("actor").put("kind", "client").put("id", key.toString()).put("auth", config.server().authMode());
        } else if (exchange.getClientInfo() != null) {
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

    private int maxTimeout() {
        return config.allInstances().stream().mapToInt(RuntimeConfig.Instance::timeoutSeconds).max().orElse(30);
    }
}
