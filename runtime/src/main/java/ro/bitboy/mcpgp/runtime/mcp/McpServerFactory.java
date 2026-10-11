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
    private final Extensions extensions;
    private final ro.bitboy.mcpgp.runtime.call.ApiToolHandler apiTools;
    private final AuditLog audit;
    private final Confirmations confirmations;
    private final Clock clock;
    /** GET operations exposed as resources (tools-contract §14.1). */
    private final Resources resources;
    /** Prompt templates from the definition (tools-contract §14.2). */
    private final Prompts prompts;
    /** Sends progress notifications while upstream calls run (tools-contract §14.3). */
    private static final java.util.concurrent.ScheduledExecutorService PROGRESS = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcpgp-progress");
        t.setDaemon(true);
        return t;
    });
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
        this.extensions = Extensions.load(manifest);
        if (!extensions.enrichers().isEmpty()) {
            apiTools.enrichment(new ro.bitboy.mcpgp.runtime.call.Enrichment(extensions.enrichers(), (exchange, tool, record) -> context(exchange, tool, record, false)));
        }
        this.resources = new Resources(manifest, config);
        this.prompts = new Prompts(manifest);
        Packs packs = new Packs(manifest, config, catalog);
        Map<String, ToolHandler> packHandlers = packs.handlers();
        // tools without an implementation in this runtime are not offered (and said so at startup)
        List<String> unsupported = new ArrayList<>();
        for (JsonNode tool : catalog.all().values()) {
            String name = tool.path("name").asText();
            ToolHandler handler = switch (tool.path("kind").asText()) {
                case "api" -> apiTools;
                case "custom" -> extensions.customTool(name, this::context);
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

    /**
     * Streamable HTTP. Handlers run on the request thread, which the servlet transport blocks until the
     * reply is written anyway. Offloading them ({@code subscribeOn(boundedElastic)}, the SDK default) races
     * when a handler throws: Reactor cancels the scheduled task from the request thread while the worker is
     * writing the error reply, the interrupt makes Tomcat fail the write, and the SDK cuts the response
     * short and drops the session.
     */
    public McpSyncServer create(io.modelcontextprotocol.spec.McpStreamableServerTransportProvider transport) {
        return configure(McpServer.sync(transport).immediateExecution(true));
    }

    private McpSyncServer configure(McpServer.SyncSpecification<?> builder) {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        for (JsonNode tool : catalog.listed()) {
            specs.add(spec(tool));
        }
        McpSchema.ServerCapabilities.Builder capabilities = McpSchema.ServerCapabilities.builder().tools(true).logging();
        if (resources.any()) {
            capabilities.resources(false, false);
            List<McpServerFeatures.SyncResourceSpecification> plain = new ArrayList<>();
            List<McpServerFeatures.SyncResourceTemplateSpecification> templates = new ArrayList<>();
            for (Resources.Entry entry : resources.entries()) {
                if (entry.isTemplate()) {
                    templates.add(new McpServerFeatures.SyncResourceTemplateSpecification(resources.template(entry),
                            (exchange, request) -> current.get().read(exchange, request)));
                } else {
                    plain.add(new McpServerFeatures.SyncResourceSpecification(resources.resource(entry),
                            (exchange, request) -> current.get().read(exchange, request)));
                }
            }
            builder.uriTemplateManagerFactory(UriTemplate::new).resources(plain).resourceTemplates(templates);
        }
        if (prompts.any()) {
            capabilities.prompts(false);
            List<McpServerFeatures.SyncPromptSpecification> promptSpecs = new ArrayList<>();
            for (McpSchema.Prompt prompt : prompts.list()) {
                promptSpecs.add(new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, request) -> current.get().prompts.get(request)));
            }
            builder.prompts(promptSpecs);
            if (!prompts.completable().isEmpty()) {
                capabilities.completions();
                List<McpServerFeatures.SyncCompletionSpecification> completions = new ArrayList<>();
                for (String name : prompts.completable()) {
                    completions.add(new McpServerFeatures.SyncCompletionSpecification(new McpSchema.PromptReference(name),
                            (exchange, request) -> current.get().prompts.complete(request)));
                }
                builder.completions(completions);
            }
        }
        builder
                .serverInfo(new McpSchema.Implementation(manifest.serverName(), manifest.serverTitle(), manifest.serverVersion()))
                .capabilities(capabilities.build())
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
        // the recording pack captures the upstream calls of this session (a discovery call's inner call keeps it)
        String outerSession = ro.bitboy.mcpgp.runtime.call.Recordings.SESSION.get();
        ro.bitboy.mcpgp.runtime.call.Recordings.SESSION.set(exchange != null ? exchange.sessionId() : outerSession);
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
            // call filters run around the rest of the call; they never see the confirmation token (extensions.md §3)
            java.util.function.Function<Map<String, Object>, Object> rest = args -> {
                if (tool.path("confirm").asBoolean(false)) {
                    confirmations.check(exchange, tool, args, token == null ? null : token.toString(),
                            state -> auditConfirm(tool, args, state, cid, exchange));
                } else if (token != null) {
                    args.put("confirm", token);       // not a confirmation tool: let validation reject it
                }
                java.util.concurrent.ScheduledFuture<?> ticker = progress(exchange, request, tool);
                try {
                    return handler.call(exchange, tool, args, record);
                } finally {
                    if (ticker != null) {
                        ticker.cancel(false);
                    }
                }
            };
            String kind = tool.path("kind").asText();
            Object value = (kind.equals("api") || kind.equals("custom")) && extensions.filtered(name)
                    ? extensions.filter(name, context(exchange, tool, record, false), arguments, rest)
                    : rest.apply(arguments);
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
            ro.bitboy.mcpgp.runtime.call.Recordings.SESSION.set(outerSession);
            traced.result(record.api, record.instance, record.contract, outcome, code);
            traced.close();
        }
        result = withCorrelation(result, cid);
        auditCall(name, tool, arguments, record, outcome, code, cid, (System.nanoTime() - started) / 1_000_000, exchange);
        return result;
    }

    /**
     * While an API tool's upstream call runs, {@code notifications/progress} every second to a client that
     * sent a {@code progressToken} (tools-contract §14.3); null when there is nothing to report.
     */
    private java.util.concurrent.ScheduledFuture<?> progress(McpSyncServerExchange exchange, McpSchema.CallToolRequest request, JsonNode tool) {
        Object token = request.meta() == null ? null : request.meta().get("progressToken");
        if (token == null || exchange == null || !tool.path("kind").asText().equals("api")) {
            return null;
        }
        long started = System.nanoTime();
        String api = tool.path("api").asText();
        return PROGRESS.scheduleAtFixedRate(() -> {
            try {
                double seconds = (System.nanoTime() - started) / 1e9;
                exchange.progressNotification(new McpSchema.ProgressNotification(token, Math.floor(seconds), null,
                        "Waiting for " + api + " (" + Math.round(seconds) + " s)"));
            } catch (RuntimeException e) {
                LOG.debug("Progress for {} couldn't be sent: {}", tool.path("name").asText(), e.getMessage());
            }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * A resource read (tools-contract §14.1): the URI becomes the operation's arguments, and the read goes
     * the way of a tool call; audited as {@code resource.read}.
     */
    public McpSchema.ReadResourceResult read(McpSyncServerExchange exchange, McpSchema.ReadResourceRequest request) {
        var resolved = resources.resolve(request.uri())
                .orElseThrow(() -> io.modelcontextprotocol.spec.McpError.RESOURCE_NOT_FOUND.apply(request.uri()));
        JsonNode resource = resolved.getKey().resource();
        String name = resource.path("name").asText();
        Map<String, Object> arguments = resolved.getValue();
        CallRecord record = new CallRecord();
        String cid = Ids.correlation();
        long started = System.nanoTime();
        MDC.put("cid", cid);
        String outcome = "ok";
        String code = null;
        ro.bitboy.mcpgp.runtime.telemetry.Telemetry.Call traced = ro.bitboy.mcpgp.runtime.telemetry.Telemetry.current()
                .call(name, resource.path("api").asText(null));
        String outerSession = ro.bitboy.mcpgp.runtime.call.Recordings.SESSION.get();
        ro.bitboy.mcpgp.runtime.call.Recordings.SESSION.set(exchange != null ? exchange.sessionId() : outerSession);
        try {
            return Resources.contents(request.uri(), resource, apiTools.call(exchange, resource, new LinkedHashMap<>(arguments), record));
        } catch (ToolError e) {
            outcome = "error";
            code = e.code();
            int jsonRpc = e.code().equals("INPUT_VALIDATION") ? McpSchema.ErrorCodes.INVALID_PARAMS : McpSchema.ErrorCodes.INTERNAL_ERROR;
            throw io.modelcontextprotocol.spec.McpError.builder(jsonRpc).message(e.code() + ": " + e.getMessage()).build();
        } catch (RuntimeException e) {
            LOG.error("Resource {} failed", request.uri(), e);
            outcome = "error";
            code = "INTERNAL";
            throw io.modelcontextprotocol.spec.McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message("The read failed; see the server log").build();
        } finally {
            MDC.remove("cid");
            ro.bitboy.mcpgp.runtime.call.Recordings.SESSION.set(outerSession);
            traced.result(record.api, record.instance, record.contract, outcome, code);
            traced.close();
            auditCall("resource.read", name, resource, arguments, record, outcome, code, cid, (System.nanoTime() - started) / 1_000_000, exchange);
        }
    }

    private void auditCall(String name, JsonNode tool, Map<String, Object> arguments, CallRecord record, String outcome,
                           String code, String cid, long latencyMs, McpSyncServerExchange exchange) {
        auditCall("tool.call", name, tool, arguments, record, outcome, code, cid, latencyMs, exchange);
    }

    private void auditCall(String type, String name, JsonNode tool, Map<String, Object> arguments, CallRecord record, String outcome,
                           String code, String cid, long latencyMs, McpSyncServerExchange exchange) {
        ObjectNode event = audit.event(type, outcome, cid);
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

    /** What an extension sees of a call; custom tools also get an {@link ro.bitboy.mcpgp.extension.ApiClient}. */
    private ro.bitboy.mcpgp.extension.ToolContext context(McpSyncServerExchange exchange, JsonNode tool, CallRecord record, boolean withApi) {
        ro.bitboy.mcpgp.extension.ApiClient api = !withApi ? null : (name, arguments) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = arguments == null || arguments.isNull() ? Map.of()
                    : new com.fasterxml.jackson.databind.ObjectMapper().convertValue(arguments, Map.class);
            McpSchema.CallToolResult result = call(exchange, new McpSchema.CallToolRequest(name, args));
            if (Boolean.TRUE.equals(result.isError())) {
                Object code = result.structuredContent() instanceof Map<?, ?> m ? m.get("code") : null;
                Object message = result.structuredContent() instanceof Map<?, ?> m ? m.get("message") : null;
                return ro.bitboy.mcpgp.extension.ToolResult.error(code == null ? "INTERNAL" : code.toString(),
                        message == null ? "the tool failed" : message.toString());
            }
            if (result.structuredContent() != null) {
                return ro.bitboy.mcpgp.extension.ToolResult.of(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(result.structuredContent()));
            }
            String text = result.content().stream().filter(c -> c instanceof McpSchema.TextContent).map(c -> ((McpSchema.TextContent) c).text())
                    .findFirst().orElse("");
            try {
                // arrays and other JSON results are sent as text; extensions get them as data
                JsonNode parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
                if (parsed != null && (parsed.isArray() || parsed.isObject())) {
                    return ro.bitboy.mcpgp.extension.ToolResult.of(parsed);
                }
            } catch (java.io.IOException e) {
                // plain text
            }
            return ro.bitboy.mcpgp.extension.ToolResult.text(text);
        };
        Object key = exchange == null || exchange.transportContext() == null ? null : exchange.transportContext().get(CLIENT_KEY);
        String caller = key != null ? key.toString() : exchange != null && exchange.getClientInfo() != null ? exchange.getClientInfo().name() : null;
        return new Extensions.Context(tool.path("name").asText(), record, caller, api, apiTools::redact);
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
