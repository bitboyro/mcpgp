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
    /** Caps on the arguments agents send (safety.inputLimits). */
    private final InputLimits inputLimits;
    private final RetainedResults batchResults;
    private final ThreadLocal<Boolean> batchApproved = ThreadLocal.withInitial(() -> false);
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
        this.inputLimits = InputLimits.of(manifest, config);
        this.catalog = new ToolCatalog(manifest, config);
        this.apiTools = new ApiToolHandler(manifest, config);
        this.batchResults = apiTools.resultStore();
        ro.bitboy.mcpgp.runtime.call.Contracts contracts = apiTools.contracts();
        contracts.onResult(this::contractChosen);
        contracts.start();
        builtins.put("result_get", (exchange, tool, args, record) -> batchResults.get(exchange, args));
        builtins.put("batch_call", (exchange, tool, args, record) -> batch(exchange, tool, args));
        builtins.put("instances_list", new InstancesList(manifest, config, contracts));
        builtins.putAll(new Discovery(manifest, config, catalog, contracts, this::call).handlers());
        this.toolsets = Toolsets.active(manifest) ? Toolsets.of(manifest) : null;
        if (toolsets != null) {
            builtins.putAll(toolsets.handlers());
        }
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
        if (manifest.multiInstance() && manifest.root().at("/instances/mixedContracts").asText("union").equals("namespaced")) {
            namespace(config, contracts);
        }
    }

    /**
     * {@code mixedContracts: namespaced} (tools-contract §3): each API tool becomes one tool per instance,
     * {@code <instance>__<tool>}, without the {@code instance} input and with only the fields of that
     * instance's contract; a tool its contract lacks isn't offered for it. Detection is waited for, so
     * each instance's tools match the version its target runs.
     */
    private void namespace(RuntimeConfig config, ro.bitboy.mcpgp.runtime.call.Contracts contracts) {
        for (JsonNode tool : List.copyOf(catalog.all().values())) {
            if (!tool.path("kind").asText().equals("api")) {
                continue;
            }
            String name = tool.path("name").asText();
            ToolHandler handler = handlers.remove(name);
            catalog.remove(List.of(name));
            if (handler == null) {
                continue;
            }
            Manifest.Api api = manifest.apis().get(tool.path("api").asText());
            for (RuntimeConfig.Instance instance : config.instances().getOrDefault(api.id(), List.of())) {
                String contract = contracts.of(instance).contract();
                String version = contract != null ? contract : api.defaultContract();   // an unavailable instance: calls say why
                if (!tool.path("variants").has(version)) {
                    continue;
                }
                ObjectNode copy = ToolCatalog.forInstance(tool, instance.id(), version);
                String copyName = copy.path("name").asText();
                catalog.add(copy);
                handlers.put(copyName, (exchange, served, arguments, record) -> {
                    Map<String, Object> withInstance = new LinkedHashMap<>(arguments);
                    withInstance.put("instance", instance.id());
                    return handler.call(exchange, tool, withInstance, record);
                });
            }
        }
    }

    /** An auto instance's detected contract, or why there's none (runtime-config §4.4), in the audit. */
    private void contractChosen(RuntimeConfig.Instance instance, ro.bitboy.mcpgp.runtime.call.Contracts.Choice choice) {
        ObjectNode event = audit.event("instance.contract", choice.available() ? "ok" : "error", Ids.correlation());
        event.putObject("actor").put("kind", "system");
        event.put("api", instance.api());
        event.put("instance", instance.id());
        if (choice.available()) {
            event.put("contract", choice.contract());
        } else {
            event.put("code", "INSTANCE_UNAVAILABLE");
        }
        ObjectNode detection = event.putObject("detection").put("selection", choice.selection());
        putIfSet(detection, "reported", choice.reported());
        putIfSet(detection, "reason", choice.reason());
        audit.write(event);
    }

    /** Which contract each instance uses (runtime-config §4.4). */
    public ro.bitboy.mcpgp.runtime.call.Contracts contracts() {
        return apiTools.contracts();
    }

    public InputLimits inputLimits() {
        return inputLimits;
    }

    public ToolCatalog catalog() {
        return catalog;
    }

    /** The toolsets, in {@code toolsets} exposure; empty otherwise. */
    public java.util.Optional<Toolsets> toolsets() {
        return java.util.Optional.ofNullable(toolsets);
    }

    /** Toolsets change the one stdio session's visible list. The tools stay registered so a disabled
     * tool can return {@code TOOLSET_DISABLED}, rather than being rejected by the SDK as unknown. */
    public void showToolsets(McpSyncServer server, String session) {
        server.notifyToolsListChanged();
    }

    /** Transport-context key with the authenticated client's key name (HTTP, static mode). */
    public static final String CLIENT_KEY = "mcpgp.client";
    /** The request's credential headers for the API (auth.md §2.4), by lower-case name: for this request only, never kept. */
    /** The MCP revision the client named in its {@code Mcp-Protocol-Version} header (HTTP). */
    public static final String REVISION_KEY = "mcpgp.revision";
    public static final String CREDENTIALS_KEY = "mcpgp.credentials";
    /** The signed-in user's access token (inbound oauth, already checked), for tokenExchange (auth.md §2.5): this request only. */
    public static final String SUBJECT_TOKEN_KEY = "mcpgp.subjectToken";

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
        // In toolsets mode API tools remain registered for dispatch; each transport tailors tools/list
        // to its session's enabled sets.
        for (JsonNode tool : toolsets == null ? catalog.listed() : catalog.all().values()) {
            specs.add(spec(tool));
        }
        McpSchema.ServerCapabilities.Builder capabilities = McpSchema.ServerCapabilities.builder().tools(true).logging();
        List<McpServerFeatures.SyncCompletionSpecification> completions = new ArrayList<>();
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
            for (String uri : resources.completable()) {
                completions.add(new McpServerFeatures.SyncCompletionSpecification(new McpSchema.ResourceReference(uri),
                        (exchange, request) -> current.get().resources.complete(request)));
            }
        }
        if (prompts.any()) {
            capabilities.prompts(false);
            List<McpServerFeatures.SyncPromptSpecification> promptSpecs = new ArrayList<>();
            for (McpSchema.Prompt prompt : prompts.list()) {
                promptSpecs.add(new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, request) -> current.get().prompts.get(request)));
            }
            builder.prompts(promptSpecs);
            for (String name : prompts.completable()) {
                completions.add(new McpServerFeatures.SyncCompletionSpecification(new McpSchema.PromptReference(name),
                        (exchange, request) -> current.get().prompts.complete(request)));
            }
        }
        if (!completions.isEmpty()) {
            capabilities.completions();
            builder.completions(completions);
        }
        builder
                .serverInfo(new McpSchema.Implementation(manifest.serverName(), manifest.serverTitle(), manifest.serverVersion()))
                .capabilities(capabilities.build())
                .requestTimeout(Duration.ofSeconds(maxTimeout() + 30L))
                .validateToolInputs(false)       // validated per instance contract by the runtime
                .jsonSchemaValidator(new LenientOutputValidator())   // results follow the target, not only its spec
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

    /** Preflights the entire plan, confirms it once, then dispatches each entry normally. */
    @SuppressWarnings("unchecked")
    private Object batch(McpSyncServerExchange exchange, JsonNode batchTool, Map<String, Object> args) {
        if (!java.util.Set.of("calls", "execution", "confirm").containsAll(args.keySet()))
            throw new ToolError("INPUT_VALIDATION", "Unknown batch argument");
        if (args.containsKey("confirm") && !(args.get("confirm") instanceof String))
            throw new ToolError("INPUT_VALIDATION", "Confirmation token must be a string");
        JsonNode policy = manifest.policies().path("batch");
        if (!(args.get("calls") instanceof List<?> calls) || calls.isEmpty() || calls.size() > policy.path("maxCalls").asInt(20))
            throw new ToolError("INPUT_VALIDATION", "Batch must contain 1 to " + policy.path("maxCalls").asInt(20) + " calls");
        String execution = String.valueOf(args.getOrDefault("execution", "sequential"));
        if (!execution.equals("sequential") && !execution.equals("parallel")) throw new ToolError("INPUT_VALIDATION", "Unknown execution policy");
        List<Map<String, Object>> plan = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        boolean needsConfirmation = false;
        for (Object item : calls) {
            if (!(item instanceof Map<?, ?> c) || !(c.get("id") instanceof String id) || id.isBlank() || id.length() > 128 || !ids.add(id)
                    || !(c.get("tool") instanceof String name) || !(c.get("arguments") instanceof Map<?, ?>))
                throw new ToolError("INPUT_VALIDATION", "Each call needs a unique id, tool, and arguments");
            if (!java.util.Set.of("id", "tool", "arguments").containsAll(c.keySet()))
                throw new ToolError("INPUT_VALIDATION", "Unknown batch entry field");
            JsonNode tool = catalog.tool(name).orElseThrow(() -> new ToolError("INPUT_VALIDATION", "Unknown tool " + name));
            if (!tool.path("kind").asText().equals("api")) throw new ToolError("INPUT_VALIDATION", "Only API operations can be batched");
            boolean read = tool.at("/annotations/readOnlyHint").asBoolean(false);
            if (!read && (!policy.path("allowWrites").asBoolean(false) || execution.equals("parallel")))
                throw new ToolError("INPUT_VALIDATION", "Writes require allowWrites and sequential execution");
            Map<String, Object> arguments = new LinkedHashMap<>((Map<String, Object>) c.get("arguments"));
            if (arguments.containsKey("confirm")) throw new ToolError("INPUT_VALIDATION", "Confirm the batch rather than individual entries");
            inputLimits.check(tool, arguments);
            if (toolsets != null && toolsets.blocked(tool.path("x-mcpgp-original").asText(name), Toolsets.session(exchange)).isPresent())
                throw new ToolError("TOOLSET_DISABLED", "Enable the toolset for " + name);
            apiTools.preflight(exchange, tool, arguments);
            needsConfirmation |= tool.path("confirm").asBoolean(false);
            plan.add(Map.of("id", id, "tool", name, "arguments", (Map<String, Object>) c.get("arguments"), "readOnly", read));
        }
        Map<String, Object> confirmed = new LinkedHashMap<>(args); Object token = confirmed.remove("confirm");
        if (needsConfirmation) confirmations.check(exchange, batchTool, confirmed, token == null ? null : token.toString(),
                state -> auditConfirm(batchTool, confirmed, state, Ids.correlation(), exchange));
        List<Map<String, Object>> results = new ArrayList<>();
        Inflight.Handle batchInflight = Inflight.current();
        String batchSession = ro.bitboy.mcpgp.runtime.call.Recordings.SESSION.get();
        java.util.function.Function<Map<String, Object>, Map<String, Object>> run = entry -> {
            Inflight.Handle previous = Inflight.bind(batchInflight);
            String previousSession = ro.bitboy.mcpgp.runtime.call.Recordings.SESSION.get();
            ro.bitboy.mcpgp.runtime.call.Recordings.SESSION.set(batchSession);
            batchApproved.set(true);
            try {
                McpSchema.CallToolResult result = call(exchange, new McpSchema.CallToolRequest((String) entry.get("tool"), (Map<String, Object>) entry.get("arguments"), null));
                String code = result.structuredContent() instanceof Map<?, ?> m ? String.valueOf(m.get("code")) : "";
                boolean unknown = !Boolean.TRUE.equals(entry.get("readOnly")) && (code.equals("UPSTREAM_TIMEOUT") || code.equals("UPSTREAM_UNREACHABLE") || code.equals("CANCELLED") || code.equals("INTERNAL"));
                Object statusValue = result.meta() == null ? null : result.meta().get("mcpgp/status");
                boolean upstreamSucceeded = statusValue instanceof Number status && status.intValue() >= 200 && status.intValue() < 300;
                Map<String, Object> row = new LinkedHashMap<>(); row.put("id", entry.get("id"));
                row.put("state", upstreamSucceeded ? "succeeded" : unknown ? "outcome_unknown" : Boolean.TRUE.equals(result.isError()) ? "failed" : "succeeded");
                if (upstreamSucceeded && Boolean.TRUE.equals(result.isError())) row.put("outputError", true);
                row.put("result", result.structuredContent() != null ? result.structuredContent() : result.content());
                if (result.meta() != null) row.put("metadata", result.meta());
                return row;
            } finally {
                batchApproved.remove(); Inflight.restore(previous);
                ro.bitboy.mcpgp.runtime.call.Recordings.SESSION.set(previousSession);
            }
        };
        if (execution.equals("parallel")) {
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(policy.path("concurrency").asInt(4))) {
                var futures = plan.stream().map(entry -> pool.submit(() -> run.apply(entry))).toList();
                for (var future : futures) { try { results.add(future.get()); } catch (Exception e) { throw new ToolError("INTERNAL", "Batch execution interrupted"); } }
            }
        } else {
            boolean stopped = false;
            for (var entry : plan) {
                if (stopped) { results.add(Map.of("id", entry.get("id"), "state", "not_started")); continue; }
                var row = run.apply(entry); results.add(row); stopped = !row.get("state").equals("succeeded") || Boolean.TRUE.equals(row.get("outputError"));
            }
        }
        if (!batchResults.enabled() && Results.json(results).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > manifest.policies().path("responseLimitKb").asInt(256) * 1024) {
            results = results.stream().map(row -> {
                Map<String, Object> summary = new LinkedHashMap<String, Object>();
                summary.put("id", row.get("id")); summary.put("state", row.get("state")); summary.put("outputOmitted", true);
                return summary;
            }).toList();
        }
        if (!batchResults.enabled() && Results.json(results).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > manifest.policies().path("responseLimitKb").asInt(256) * 1024 - 256) {
            Map<String, Long> counts = results.stream().collect(java.util.stream.Collectors.groupingBy(row -> row.get("state").toString(), java.util.stream.Collectors.counting()));
            return Map.of("counts", counts, "outputOmitted", true, "hint", "Enable retained results or submit a smaller batch to inspect individual outputs");
        }
        ToolOutput output = new ToolOutput(Map.of("batchId", "b_" + java.util.UUID.randomUUID(), "results", results), null, Map.of());
        return new Discovery.Passthrough(Results.ok(batchResults.format(exchange, output), null));
    }

    /** Dispatches a call after confirmation; every failure becomes an audited error result. */
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
        Inflight.Handle outerInflight = Inflight.bind(Inflight.of(request.meta()) != null ? Inflight.of(request.meta()) : Inflight.current());
        try {
            if (tool == null) {
                throw new ToolError("INPUT_VALIDATION", "Unknown tool " + name);
            }
            if (Inflight.current() != null && Inflight.current().cancelled()) {
                throw new ToolError("CANCELLED", "The client cancelled this call before it started");
            }
            inputLimits.check(tool, arguments);
            if (toolsets != null && tool.path("kind").asText().equals("api")) {
                java.util.Optional<List<String>> blocked = toolsets.blocked(tool.path("x-mcpgp-original").asText(name), Toolsets.session(exchange));
                if (blocked.isPresent()) {
                    throw new ToolError("TOOLSET_DISABLED", blocked.get().isEmpty() ? "Tool " + name + " is in no toolset"
                            : "Tool " + name + " is in the toolset " + String.join(" or ", blocked.get()) + ", which is not enabled",
                            "Enable it with toolset_enable, then call the tool");
                }
            }
            ToolHandler handler = handlers.get(name);
            Object token = tool.path("handler").asText().equals("batch_call") ? null : arguments.remove("confirm");
            // call filters run around the rest of the call; they never see the confirmation token (extensions.md §3)
            java.util.function.Function<Map<String, Object>, Object> rest = args -> {
                if (tool.path("confirm").asBoolean(false) && !batchApproved.get()) {
                    confirmations.check(exchange, tool, args, token == null ? null : token.toString(),
                            state -> auditConfirm(tool, args, state, cid, exchange));
                } else if (token != null) {
                    args.put("confirm", token);       // not a confirmation tool: let validation reject it
                }
                java.util.concurrent.ScheduledFuture<?> ticker = progress(exchange, request, tool, record);
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
            outcome = e.code().equals("CONFIRMATION_REQUIRED") || e.code().equals("NOT_AVAILABLE_ON_INSTANCE") ? "denied"
                    : e.code().equals("CANCELLED") ? "cancelled" : "error";
            code = e.code();
            if (e.code().startsWith("UPSTREAM_") || e.code().equals("AUTH_DOWNSTREAM") || e.code().equals("RATE_LIMITED")) {
                notifyClient(exchange, McpSchema.LoggingLevel.WARNING, name + ": " + e.code() + ": " + e.getMessage());
            }
            result = Results.error(e, e.meta());
        } catch (RuntimeException e) {
            LOG.error("Tool {} failed", name, e);
            outcome = "error";
            code = "INTERNAL";
            result = Results.error(new ToolError("INTERNAL", "The tool failed unexpectedly; see the server log"), null);
        } finally {
            if (Inflight.current() != null && Inflight.current().cancelled()) {
                record.cancelReason = Inflight.current().reason();
            }
            Inflight.restore(outerInflight);
            MDC.remove("cid");
            ro.bitboy.mcpgp.runtime.call.Recordings.SESSION.set(outerSession);
            traced.result(record.api, record.instance, record.contract, outcome, code);
            traced.mcp(config.server().transport(), record.progressRequested ? record.progressSent.get() : -1);
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
    private java.util.concurrent.ScheduledFuture<?> progress(McpSyncServerExchange exchange, McpSchema.CallToolRequest request, JsonNode tool, CallRecord record) {
        Object token = request.meta() == null ? null : request.meta().get("progressToken");
        if (token == null || exchange == null || !tool.path("kind").asText().equals("api")) {
            return null;
        }
        record.progressRequested = true;
        long started = System.nanoTime();
        String api = tool.path("api").asText();
        Object chosen = request.arguments() == null ? null : request.arguments().get("instance");
        // the call fails after the instance's timeout, so that is the most the wait can come to
        double total = config.allInstances().stream().filter(i -> i.api().equals(api) && (chosen == null || i.id().equals(chosen.toString())))
                .mapToInt(RuntimeConfig.Instance::timeoutSeconds).max().orElse(0);
        String where = chosen == null ? api : api + " on " + chosen;
        return PROGRESS.scheduleAtFixedRate(() -> {
            try {
                double seconds = Math.round((System.nanoTime() - started) / 1e8) / 10.0;      // always larger than the last (a second apart)
                exchange.progressNotification(new McpSchema.ProgressNotification(token, seconds, total > 0 ? Math.max(total, seconds) : null,
                        "Waiting for " + where + " (" + Math.round(seconds) + (total > 0 ? " of " + Math.round(total) : "") + " s)"));
                record.progressSent.incrementAndGet();
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
        Inflight.Handle outerInflight = Inflight.bind(Inflight.of(request.meta()) != null ? Inflight.of(request.meta()) : Inflight.current());
        try {
            if (Inflight.current() != null && Inflight.current().cancelled()) {
                throw new ToolError("CANCELLED", "The client cancelled this read before it started");
            }
            return Resources.contents(request.uri(), resource, apiTools.call(exchange, resource, new LinkedHashMap<>(arguments), record));
        } catch (ToolError e) {
            outcome = e.code().equals("CANCELLED") ? "cancelled" : "error";
            code = e.code();
            int jsonRpc = e.code().equals("INPUT_VALIDATION") ? McpSchema.ErrorCodes.INVALID_PARAMS : McpSchema.ErrorCodes.INTERNAL_ERROR;
            throw io.modelcontextprotocol.spec.McpError.builder(jsonRpc).message(e.code() + ": " + e.getMessage()).build();
        } catch (RuntimeException e) {
            LOG.error("Resource {} failed", request.uri(), e);
            outcome = "error";
            code = "INTERNAL";
            throw io.modelcontextprotocol.spec.McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message("The read failed; see the server log").build();
        } finally {
            if (Inflight.current() != null && Inflight.current().cancelled()) {
                record.cancelReason = Inflight.current().reason();
            }
            Inflight.restore(outerInflight);
            MDC.remove("cid");
            ro.bitboy.mcpgp.runtime.call.Recordings.SESSION.set(outerSession);
            traced.result(record.api, record.instance, record.contract, outcome, code);
            traced.mcp(config.server().transport(), record.progressRequested ? record.progressSent.get() : -1);
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
            putIfSet(upstream, "auth", record.auth);
            putIfSet(upstream, "body", record.errorBody);
        }
        event.put("latencyMs", latencyMs);
        mcpDetails(event, record, outcome, exchange);
        boolean sensitive = tool != null && tool.path("sensitive").asBoolean(false);
        event.set("args", Arguments.render(arguments, sensitive && audit.argumentsMode().equals("hashed") ? "redacted" : audit.argumentsMode()));
        if (!record.files.isEmpty()) {
            var files = event.putArray("files");
            record.files.forEach(f -> files.addObject().put("name", f.name()).put("sizeBytes", f.sizeBytes()).put("sha256", f.sha256()));
        }
        audit.write(event);
    }

    /**
     * An MCP log message to the client ({@code notifications/message}); the SDK sends it only when the
     * level is at or above what the client set with {@code logging/setLevel}. Never fails the call.
     */
    private static void notifyClient(McpSyncServerExchange exchange, McpSchema.LoggingLevel level, String message) {
        if (exchange == null) {
            return;
        }
        try {
            exchange.loggingNotification(McpSchema.LoggingMessageNotification.builder().level(level).logger("mcpgp").data(message).build());
        } catch (RuntimeException e) {
            LOG.debug("MCP log message couldn't be sent: {}", e.getMessage());
        }
    }

    /**
     * What the protocol side of the call did: the transport and the revision the client named (HTTP), progress
     * (asked for, and how many notifications went out), and why a cancelled call was cancelled. Also one log
     * line: INFO when the call was cancelled or refused, DEBUG otherwise.
     */
    private void mcpDetails(ObjectNode event, CallRecord record, String outcome, McpSyncServerExchange exchange) {
        ObjectNode mcp = event.putObject("mcp").put("transport", config.server().transport());
        Object revision = exchange == null || exchange.transportContext() == null ? null : exchange.transportContext().get(REVISION_KEY);
        putIfSet(mcp, "revision", revision == null ? null : revision.toString());
        if (record.progressRequested) {
            mcp.putObject("progress").put("requested", true).put("sent", record.progressSent.get());
        }
        if (outcome.equals("cancelled")) {
            mcp.putObject("cancelled").put("reason", record.cancelReason == null ? "" : record.cancelReason);
        }
        String line = "{} {} (transport {}{}{}{})";
        Object[] arguments = {event.path("type").asText(), outcome, config.server().transport(),
                revision == null ? "" : ", revision " + revision,
                record.progressRequested ? ", progress " + record.progressSent.get() + " sent" : "",
                outcome.equals("cancelled") ? ", cancelled" + (record.cancelReason == null ? "" : ": " + record.cancelReason) : ""};
        if (outcome.equals("cancelled") || outcome.equals("denied")) {
            LOG.info(line, arguments);
        } else {
            LOG.debug(line, arguments);
        }
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
                // a list or value the server wrapped for MCP reaches the extension as the API gave it
                Object wrapped = result.meta() == null ? null : result.meta().get("mcpgp/wrapped");
                Object value = wrapped != null && result.structuredContent() instanceof Map<?, ?> m ? m.get(wrapped.toString()) : result.structuredContent();
                return ro.bitboy.mcpgp.extension.ToolResult.of(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(value));
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
