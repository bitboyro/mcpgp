package ro.bitboy.mcpgp.packager.trial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import ro.bitboy.mcpgp.core.manifest.CanonicalJson;
import ro.bitboy.mcpgp.core.openapi.ApiModel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * {@code mcpgp test} (cli.md §3, ci-pipeline.md §5) and trying tools on the platform: starts the server mcpgp packages (the same runtime
 * jar, in stdio mode) with the compiled manifest, against a {@link SpecMock} instance named {@code mock},
 * then runs a smoke check and the test cases.
 */
public final class MockRun implements AutoCloseable {

    public static final String INSTANCE = "mock";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 60;

    /** One case's outcome; {@code problem} is null when it passed. */
    public record Outcome(String name, String file, String problem) {
        public boolean passed() {
            return problem == null;
        }
    }

    private final JsonNode manifest;
    private final SpecMock mock;
    private final Process process;
    private final OutputStream stdin;
    private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
    private final StringBuilder log = new StringBuilder();
    private int id;

    public MockRun(JsonNode manifest, Collection<ApiModel.Contract> contracts, InputStream runtimeJar, Path dir) throws IOException {
        this(manifest, contracts, runtimeJar, dir, null);
    }

    /** @param root the definition's folder, where extension artifacts are copied from (next to the manifest the server runs) */
    public MockRun(JsonNode manifest, Collection<ApiModel.Contract> contracts, InputStream runtimeJar, Path dir, Path root) throws IOException {
        this.manifest = manifest;
        this.mock = new SpecMock(contracts);
        Path jar = dir.resolve("runtime.jar");
        try (runtimeJar) {
            Files.copy(runtimeJar, jar);
        }
        for (JsonNode artifact : manifest.at("/extensions/artifacts")) {
            Path target = dir.resolve(artifact.path("path").asText()).normalize();
            if (root == null || !target.startsWith(dir)) {
                throw new IOException("extension artifact " + artifact.path("path").asText() + " can't be copied for the test");
            }
            Files.createDirectories(target.getParent());
            Files.copy(root.resolve(artifact.path("path").asText()), target);
        }
        Path manifestFile = dir.resolve("manifest.json");
        Files.writeString(manifestFile, CanonicalJson.pretty(manifest));
        Path config = dir.resolve("config.yaml");
        Files.writeString(config, JSON.writeValueAsString(config(manifest, mock.address())));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        process = new ProcessBuilder(java, "-jar", jar.toString(), "--manifest", manifestFile.toString(), "--config", config.toString(),
                "--storage.root", dir.resolve("data").toString()).directory(dir.toFile()).start();
        stdin = process.getOutputStream();
        Thread reader = new Thread(() -> read(process.getInputStream()), "mcpgp-test-stdout");
        reader.setDaemon(true);
        reader.start();
        Thread errors = new Thread(() -> drain(process.getErrorStream()), "mcpgp-test-stderr");
        errors.setDaemon(true);
        errors.start();
    }

    /** Runtime config with every API's instance at the mock: {@code instance}, {@code instances} or {@code apis}. */
    static ObjectNode config(JsonNode manifest, String baseUrl) {
        ObjectNode config = JSON.createObjectNode();
        config.putObject("server").put("transport", "stdio");
        boolean multi = manifest.at("/instances/mode").asText("single").equals("multi");
        List<String> apis = new ArrayList<>();
        manifest.path("apis").forEach(a -> apis.add(a.path("id").asText()));
        if (apis.size() <= 1) {
            if (multi) {
                config.putArray("instances").addObject().put("id", INSTANCE).put("baseUrl", baseUrl);
            } else {
                config.putObject("instance").put("baseUrl", baseUrl);
            }
            return config;
        }
        ObjectNode byApi = config.putObject("apis");
        for (String api : apis) {
            if (multi) {
                byApi.putObject(api).putArray("instances").addObject().put("id", INSTANCE).put("baseUrl", baseUrl);
            } else {
                byApi.putObject(api).putObject("instance").put("baseUrl", baseUrl);
            }
        }
        return config;
    }

    /** Starts the MCP session and checks that the server lists its tools; null when it did. */
    public String smoke() {
        try {
            JsonNode init = request("initialize", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "mcpgp-test", "version", ro.bitboy.mcpgp.core.Compiler.VERSION)));
            if (init.has("error")) {
                return "initialize failed: " + init.at("/error/message").asText();
            }
            notify("notifications/initialized");
            JsonNode list = request("tools/list", Map.of());
            if (list.has("error")) {
                return "tools/list failed: " + list.at("/error/message").asText();
            }
            if (list.at("/result/tools").isEmpty()) {
                return "the server lists no tools";
            }
            return null;
        } catch (IOException e) {
            return "the server did not start: " + e.getMessage() + serverLog();
        }
    }

    int listedTools() throws IOException {
        return request("tools/list", Map.of()).at("/result/tools").size();
    }

    /** Runs the cases of one file ({@code cases: [{name, tool, args, expect}]}). */
    public List<Outcome> run(Path file) throws IOException {
        JsonNode suite = new YAMLMapper().readTree(file.toFile());
        List<Outcome> outcomes = new ArrayList<>();
        int index = 0;
        for (JsonNode c : suite.path("cases")) {
            index++;
            String name = c.path("name").asText("case " + index);
            outcomes.add(new Outcome(name, file.getFileName().toString(), check(c)));
        }
        return outcomes;
    }

    private String check(JsonNode c) throws IOException {
        String tool = c.path("tool").asText("");
        if (tool.isEmpty()) {
            return "the case names no tool";
        }
        if (tool(tool) == null) {
            return "no tool named " + tool + " in the build";
        }
        JsonNode expect = c.path("expect");
        if (!expect.has("ok") && !expect.has("code")) {
            return "expect needs ok: true or code: <ERROR_CODE>";
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> args = c.path("args").isObject() ? JSON.convertValue(c.path("args"), Map.class) : new java.util.LinkedHashMap<>();
        if (INSTANCE.equals(args.get("instance")) && !takesInstance(tool)) {
            args.remove("instance");          // single-instance builds have no instance argument
        }
        JsonNode response = request("tools/call", Map.of("name", tool, "arguments", args));
        if (response.has("error")) {
            return "the call failed: " + response.at("/error/message").asText();
        }
        JsonNode result = response.path("result");
        boolean isError = result.path("isError").asBoolean(false);
        String code = result.at("/structuredContent/code").asText(isError ? "ERROR" : "");
        String message = isError ? result.at("/structuredContent/message").asText(text(result)) : "";
        if (expect.has("code")) {
            String wanted = expect.path("code").asText();
            if (!isError) {
                return "expected " + wanted + ", but the call succeeded";
            }
            return code.equals(wanted) ? null : "expected " + wanted + ", got " + code + ": " + message;
        }
        if (expect.path("ok").asBoolean(true) == isError) {
            return isError ? "expected success, got " + code + ": " + message : "expected an error, but the call succeeded";
        }
        if (expect.has("structured")) {
            String mismatch = contains(structured(result), expect.path("structured"), "");
            if (mismatch != null) {
                return "structured content: " + mismatch;
            }
        }
        return null;
    }

    private JsonNode tool(String name) {
        for (JsonNode t : manifest.path("tools")) {
            if (t.path("name").asText().equals(name)) {
                return t;
            }
        }
        return null;
    }

    private boolean takesInstance(String tool) {
        return tool(tool).at("/inputSchema/properties").has("instance");
    }

    /** The structured content, else the text content when it is JSON (tools without an output schema). */
    private static JsonNode structured(JsonNode result) {
        if (result.has("structuredContent")) {
            return result.get("structuredContent");
        }
        try {
            return JSON.readTree(text(result));
        } catch (IOException e) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
    }

    private static String text(JsonNode result) {
        for (JsonNode c : result.path("content")) {
            if (c.path("type").asText().equals("text")) {
                return c.path("text").asText();
            }
        }
        return "";
    }

    /** Whether {@code actual} has every field and value of {@code expected} (objects partially, the rest equal). */
    static String contains(JsonNode actual, JsonNode expected, String at) {
        if (expected.isObject()) {
            if (!actual.isObject()) {
                return (at.isEmpty() ? "the result" : at) + " is not an object: " + brief(actual);
            }
            Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                String path = at + "/" + f.getKey();
                if (!actual.has(f.getKey())) {
                    return path + " is missing";
                }
                String inner = contains(actual.get(f.getKey()), f.getValue(), path);
                if (inner != null) {
                    return inner;
                }
            }
            return null;
        }
        boolean equal = expected.isNumber() && actual.isNumber() ? expected.decimalValue().compareTo(actual.decimalValue()) == 0
                : expected.equals(actual);
        return equal ? null : (at.isEmpty() ? "the result" : at) + " is " + brief(actual) + ", expected " + expected;
    }

    private static String brief(JsonNode value) {
        String text = value.isMissingNode() ? "missing" : value.toString();
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    /**
     * One tool call, as an agent makes it: the JSON-RPC answer ({@code result} or {@code error}) and the
     * requests the mock received for it. {@code instance: mock} is dropped for tools without an instance.
     */
    public JsonNode call(String tool, Map<String, Object> arguments) throws IOException {
        Map<String, Object> args = new java.util.LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        JsonNode served = tool(tool);
        if (served != null && INSTANCE.equals(args.get("instance")) && !served.at("/inputSchema/properties").has("instance")) {
            args.remove("instance");
        }
        mock.drain();
        ObjectNode answer = JSON.createObjectNode();
        JsonNode response = request("tools/call", Map.of("name", tool, "arguments", args));
        if (response.has("error")) {
            answer.set("error", response.get("error"));
        } else {
            answer.set("result", response.path("result"));
        }
        answer.set("requests", JSON.valueToTree(mock.drain()));
        return answer;
    }

    /**
     * The manifest without its extensions (custom tools, enrichers, call filters): for trying a server
     * where its builders' code must not run (the platform); the list says what was left out.
     */
    public static ObjectNode withoutExtensions(JsonNode manifest, List<String> leftOut) {
        ObjectNode copy = manifest.deepCopy();
        JsonNode extensions = copy.remove("extensions");
        if (extensions != null) {
            extensions.path("enrichers").forEach(e -> leftOut.add("request enricher " + e.path("handler").asText()));
            extensions.path("callFilters").forEach(f -> leftOut.add("call filter " + f.path("handler").asText()));
        }
        ArrayNode tools = copy.withArray("tools");
        for (int i = tools.size() - 1; i >= 0; i--) {
            if (tools.get(i).path("kind").asText().equals("custom")) {
                leftOut.add("custom tool " + tools.get(i).path("name").asText());
                tools.remove(i);
            }
        }
        return copy;
    }

    /** The test case files of a suite directory: its *.yaml and *.yml files, sorted. */
    public static List<Path> suite(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.toString().endsWith(".yaml") || f.toString().endsWith(".yml")).sorted().toList();
        }
    }

    private JsonNode request(String method, Object params) throws IOException {
        int requestId = ++id;
        send(Map.of("jsonrpc", "2.0", "id", requestId, "method", method, "params", params));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (true) {
            JsonNode message;
            try {
                message = messages.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            if (message == null) {
                throw new IOException(process.isAlive() ? "no answer to " + method + " within " + TIMEOUT_SECONDS + " s"
                        : "the server exited with code " + process.exitValue());
            }
            if (message.path("id").asInt(-1) == requestId) {
                return message;
            }
        }
    }

    private void notify(String method) throws IOException {
        send(Map.of("jsonrpc", "2.0", "method", method));
    }

    private void send(Object message) throws IOException {
        stdin.write(JSON.writeValueAsBytes(message));
        stdin.write('\n');
        stdin.flush();
    }

    private void read(InputStream out) {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(out, StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.startsWith("{")) {
                    try {
                        messages.add(JSON.readTree(line));
                    } catch (IOException e) {
                        // not a JSON-RPC message
                    }
                }
            }
        } catch (IOException e) {
            // the process ended
        }
    }

    private void drain(InputStream err) {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                synchronized (log) {
                    if (log.length() < 20_000) {
                        log.append(line).append('\n');
                    }
                }
            }
        } catch (IOException e) {
            // the process ended
        }
    }

    /** The server's stderr so far, for a start that failed. */
    public String serverLog() {
        synchronized (log) {
            return log.isEmpty() ? "" : "\n" + log.toString().strip();
        }
    }

    @Override
    public void close() {
        try {
            stdin.close();
        } catch (IOException e) {
            // already closed
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        mock.close();
    }

    /** Results as JSON for {@code --json}. */
    public static ArrayNode json(List<Outcome> outcomes) {
        ArrayNode list = JSON.createArrayNode();
        outcomes.forEach(o -> {
            ObjectNode node = list.addObject().put("file", o.file()).put("name", o.name()).put("passed", o.passed());
            if (o.problem() != null) {
                node.put("problem", o.problem());
            }
        });
        return list;
    }
}
