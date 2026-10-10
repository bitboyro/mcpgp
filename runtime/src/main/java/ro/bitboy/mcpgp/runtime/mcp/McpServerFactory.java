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

import java.time.Duration;
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

    public McpServerFactory(Manifest manifest, RuntimeConfig config) {
        this.manifest = manifest;
        this.config = config;
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

    /** Dispatches a call by tool kind; every failure becomes an {@code isError} result. */
    public McpSchema.CallToolResult call(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        String name = request.name();
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        JsonNode tool = catalog.tool(name).orElse(null);
        try {
            if (tool == null) {
                throw new ToolError("INPUT_VALIDATION", "Unknown tool " + name);
            }
            ToolHandler handler = handler(tool);
            return Results.ok(handler.call(exchange, tool, arguments), null);
        } catch (ToolError e) {
            return Results.error(e, e.meta());
        } catch (RuntimeException e) {
            LOG.error("Tool {} failed", name, e);
            return Results.error(new ToolError("INTERNAL", "The tool failed unexpectedly; see the server log"), null);
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
