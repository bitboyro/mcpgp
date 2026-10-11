package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import ro.bitboy.mcpgp.extension.ApiClient;
import ro.bitboy.mcpgp.extension.CallFilter;
import ro.bitboy.mcpgp.extension.CustomTool;
import ro.bitboy.mcpgp.extension.RequestEnricher;
import ro.bitboy.mcpgp.extension.ToolContext;
import ro.bitboy.mcpgp.extension.ToolResult;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.manifest.ManifestException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The extensions a package contains (extensions.md §4): the declared artifacts are loaded (their
 * checksums checked) and the declared handler classes instantiated; nothing else is picked up. A
 * missing artifact or class, a wrong interface or a checksum mismatch fails startup.
 */
final class Extensions {

    private static final Logger LOG = LoggerFactory.getLogger(Extensions.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Where the packager puts the artifacts inside the server jar (next to the embedded manifest). */
    static final String CLASSPATH_FOLDER = "/mcpgp/artifacts/";

    /** A call filter and the tools it applies to (null: every API and custom tool). */
    record Filter(CallFilter filter, Set<String> tools) {
        boolean appliesTo(String tool) {
            return tools == null || tools.contains(tool);
        }
    }

    static final Extensions NONE = new Extensions(Map.of(), List.of(), List.of());

    private final Map<String, CustomTool> customTools;
    private final List<RequestEnricher> enrichers;
    private final List<Filter> filters;

    private Extensions(Map<String, CustomTool> customTools, List<RequestEnricher> enrichers, List<Filter> filters) {
        this.customTools = customTools;
        this.enrichers = enrichers;
        this.filters = filters;
    }

    static Extensions load(Manifest manifest) {
        JsonNode section = manifest.root().path("extensions");
        List<JsonNode> custom = new ArrayList<>();
        manifest.root().path("tools").forEach(t -> {
            if (t.path("kind").asText().equals("custom")) {
                custom.add(t);
            }
        });
        if (section.isMissingNode() && custom.isEmpty()) {
            return NONE;
        }
        List<URL> urls = new ArrayList<>();
        for (JsonNode artifact : section.path("artifacts")) {
            urls.add(artifact(manifest, artifact.path("path").asText(), artifact.path("sha256").asText()));
        }
        // the parent is the runtime's loader: extensions see the extension API and Jackson the server brings
        ClassLoader loader = new URLClassLoader("mcpgp-extensions", urls.toArray(URL[]::new), Extensions.class.getClassLoader());
        Map<String, CustomTool> tools = new LinkedHashMap<>();
        for (JsonNode tool : custom) {
            tools.put(tool.path("name").asText(), instance(loader, tool.path("handler").asText(), CustomTool.class));
        }
        List<RequestEnricher> enrichers = new ArrayList<>();
        section.path("enrichers").forEach(e -> enrichers.add(instance(loader, e.path("handler").asText(), RequestEnricher.class)));
        List<Filter> filters = new ArrayList<>();
        section.path("callFilters").forEach(f -> {
            Set<String> names = null;
            if (f.has("tools")) {
                names = new LinkedHashSet<>();
                for (JsonNode n : f.path("tools")) {
                    names.add(n.asText());
                }
            }
            filters.add(new Filter(instance(loader, f.path("handler").asText(), CallFilter.class), names));
        });
        LOG.info("Extensions: {} custom tools, {} enrichers, {} call filters from {} artifacts", tools.size(), enrichers.size(),
                filters.size(), urls.size());
        return new Extensions(Map.copyOf(tools), List.copyOf(enrichers), List.copyOf(filters));
    }

    /** The artifact's bytes, from the server jar or next to a manifest file, checked against the manifest's checksum. */
    private static URL artifact(Manifest manifest, String path, String sha256) {
        byte[] bytes;
        try (InputStream in = Extensions.class.getResourceAsStream(CLASSPATH_FOLDER + path)) {
            if (in != null) {
                bytes = in.readAllBytes();
            } else {
                Path file = manifest.folder().map(f -> f.resolve(path).normalize()).filter(Files::isRegularFile)
                        .orElseThrow(() -> new ManifestException("Extension artifact " + path + " is not in this package"));
                bytes = Files.readAllBytes(file);
            }
            String actual = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!actual.equals(sha256)) {
                throw new ManifestException("Extension artifact " + path + " does not match its checksum in the manifest (" + actual
                        + " instead of " + sha256 + "); the package was changed");
            }
            Path copy = Files.createTempFile("mcpgp-extension-", ".jar");
            copy.toFile().deleteOnExit();
            Files.write(copy, bytes);
            return copy.toUri().toURL();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new ManifestException("Extension artifact " + path + " cannot be read: " + e.getMessage());
        }
    }

    private static <T> T instance(ClassLoader loader, String handler, Class<T> kind) {
        Class<?> type;
        try {
            type = Class.forName(handler, true, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new ManifestException("Extension class " + handler + " is not in the declared artifacts (" + e + ")");
        }
        if (!kind.isAssignableFrom(type)) {
            throw new ManifestException("Extension class " + handler + " must implement " + kind.getName());
        }
        try {
            return kind.cast(type.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new ManifestException("Extension class " + handler + " cannot be created (it needs a public no-argument constructor): " + e);
        }
    }

    List<RequestEnricher> enrichers() {
        return enrichers;
    }

    boolean filtered(String tool) {
        return filters.stream().anyMatch(f -> f.appliesTo(tool));
    }

    /** A custom tool as a handler: its result becomes the tool's value; an error result becomes a {@link ToolError}. */
    ToolHandler customTool(String name, Contexts contexts) {
        CustomTool tool = customTools.get(name);
        if (tool == null) {
            return null;
        }
        return (exchange, served, arguments, record) -> {
            ToolResult result;
            try {
                result = tool.call(contexts.of(exchange, served, record, true), JSON.valueToTree(arguments));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            if (result == null) {
                throw new IllegalStateException("custom tool " + name + " returned no result");
            }
            return value(result);
        };
    }

    /**
     * Runs the call through the filters that apply to it, in order; {@code inner} is the rest of the
     * call (confirmation and the tool). A result a filter passes on unchanged keeps its original form
     * (an error stays the original error, with its hints and confirmation details).
     */
    Object filter(String tool, ToolContext ctx, Map<String, Object> arguments, java.util.function.Function<Map<String, Object>, Object> inner) {
        List<Filter> applying = filters.stream().filter(f -> f.appliesTo(tool)).toList();
        Map<ToolResult, Object> originals = new IdentityHashMap<>();
        CallFilter.Chain chain = chain(applying, 0, ctx, originals, inner);
        ToolResult result;
        try {
            result = chain.proceed(JSON.valueToTree(arguments));
        } catch (ToolError e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (result == null) {
            throw new IllegalStateException("a call filter returned no result");
        }
        Object original = originals.get(result);
        if (original instanceof ToolError error) {
            throw error;
        }
        return original != null ? original : value(result);
    }

    private CallFilter.Chain chain(List<Filter> applying, int index, ToolContext ctx, Map<ToolResult, Object> originals,
                                   java.util.function.Function<Map<String, Object>, Object> inner) {
        if (index == applying.size()) {
            return arguments -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = arguments == null || arguments.isNull() ? new LinkedHashMap<>()
                        : JSON.convertValue(arguments, LinkedHashMap.class);
                try {
                    Object value = inner.apply(args);
                    ToolResult result = result(value);
                    originals.put(result, value);
                    return result;
                } catch (ToolError e) {
                    ToolResult result = ToolResult.error(e.code(), e.getMessage());
                    originals.put(result, e);
                    return result;
                }
            };
        }
        CallFilter filter = applying.get(index).filter();
        CallFilter.Chain next = chain(applying, index + 1, ctx, originals, inner);
        return arguments -> filter.filter(ctx, arguments, next);
    }

    /** A tool's value as an extension sees it. */
    static ToolResult result(Object value) {
        if (value instanceof ToolOutput output) {
            return output.structured() != null ? ToolResult.of(JSON.valueToTree(output.structured())) : ToolResult.text(output.text());
        }
        if (value instanceof String text) {
            return ToolResult.text(text);
        }
        return ToolResult.of(JSON.valueToTree(value));
    }

    /** An extension's result as a tool value. */
    static Object value(ToolResult result) {
        if (result.isError()) {
            throw new ToolError(result.code(), result.message());
        }
        if (result.text() != null) {
            return new ToolOutput(null, result.text(), null);
        }
        JsonNode structured = result.structured();
        Object converted = JSON.convertValue(structured, Object.class);
        return structured.isObject() ? converted : new ToolOutput(null, structured.toString(), null);
    }

    /** Builds the context an extension sees for a call. */
    @FunctionalInterface
    interface Contexts {
        ToolContext of(McpSyncServerExchange exchange, JsonNode tool, CallRecord record, boolean withApi);
    }

    /** The read-only view of a call that extensions get (extensions.md §2). */
    static final class Context implements ToolContext {
        private final String tool;
        private final CallRecord record;
        private final String caller;
        private final String correlationId;
        private final ApiClient api;
        private final Log log;

        Context(String tool, CallRecord record, String caller, ApiClient api, UnaryOperator<String> redact) {
            this.tool = tool;
            this.record = record;
            this.caller = caller;
            this.correlationId = MDC.get("cid");
            this.api = api;
            Logger logger = LoggerFactory.getLogger("mcpgp.extension." + tool);
            this.log = new Log() {
                @Override
                public void info(String message) {
                    logger.info("{}", redact.apply(String.valueOf(message)));
                }

                @Override
                public void warn(String message) {
                    logger.warn("{}", redact.apply(String.valueOf(message)));
                }

                @Override
                public void error(String message, Throwable error) {
                    logger.error("{}: {}", redact.apply(String.valueOf(message)), error == null ? "" : redact.apply(error.toString()));
                }
            };
        }

        @Override
        public String tool() {
            return tool;
        }

        @Override
        public Optional<String> instance() {
            return Optional.ofNullable(record == null ? null : record.instance);
        }

        @Override
        public Optional<String> contract() {
            return Optional.ofNullable(record == null ? null : record.contract);
        }

        @Override
        public String correlationId() {
            return correlationId;
        }

        @Override
        public Optional<String> caller() {
            return Optional.ofNullable(caller);
        }

        @Override
        public Log log() {
            return log;
        }

        @Override
        public ApiClient api() {
            if (api == null) {
                throw new IllegalStateException("only custom tools can call API tools");
            }
            return api;
        }
    }
}
