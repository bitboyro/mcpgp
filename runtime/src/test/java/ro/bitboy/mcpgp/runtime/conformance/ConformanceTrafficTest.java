package ro.bitboy.mcpgp.runtime.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import ro.bitboy.mcpgp.runtime.call.MockTarget;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.mcp.McpServerFactory;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The traffic part of the conformance suite (conformance.md §2, tools-contract §13.5): each step's
 * MCP call runs against a mock target; the request the target receives and the result the client
 * gets must match the case.
 */
class ConformanceTrafficTest {

    private static final Path CASES = Path.of("..", "conformance", "cases").toAbsolutePath().normalize();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    @TestFactory
    Stream<DynamicContainer> cases() throws IOException {
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(CASES)) {
            walk.filter(p -> p.getFileName().toString().equals("traffic.yaml")).sorted().forEach(p -> dirs.add(p.getParent()));
        }
        assertThat(dirs).isNotEmpty();
        return dirs.stream().map(dir -> {
            try {
                JsonNode traffic = YAML.readTree(dir.resolve("traffic.yaml").toFile());
                List<DynamicTest> steps = new ArrayList<>();
                // steps share one server and target, in order
                Case running = new Case(dir);
                for (JsonNode step : traffic.path("steps")) {
                    steps.add(DynamicTest.dynamicTest(step.path("name").asText(), () -> running.run(step)));
                }
                steps.add(DynamicTest.dynamicTest("(stop mock)", running::close));
                return DynamicContainer.dynamicContainer(CASES.relativize(dir).toString(), steps);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static final class Case implements AutoCloseable {
        private final Path dir;
        private MockTarget target;
        private McpServerFactory server;

        Case(Path dir) {
            this.dir = dir;
        }

        private void start() throws IOException {
            target = new MockTarget();
            Manifest manifest = Manifest.file(dir.resolve("expected/manifest.json"));
            Path work = Files.createTempDirectory("mcpgp-conformance");
            // instances point to `mock`: the mock target's address
            String runtime = Files.readString(dir.resolve("runtime.yaml")).replaceAll("baseUrl:\\s*mock\\b", "baseUrl: \"" + target.address() + "\"");
            Path config = work.resolve("server.yaml");
            Files.writeString(config, runtime);
            RuntimeConfig loaded = new ConfigLoader(new Schemas()).load(manifest, new ConfigLoader.Options(Optional.of(config), work,
                    Map.of(), Map.of("storage.root", work.resolve("data").toString())));
            server = new McpServerFactory(manifest, loaded);
        }

        void run(JsonNode step) throws IOException {
            if (server == null) {
                start();
            }
            JsonNode mcp = step.path("mcp");
            if (!mcp.path("method").asText().equals("tools/call")) {
                fail("unsupported step method " + mcp.path("method").asText());
            }
            if (step.has("respond")) {
                JsonNode respond = step.get("respond");
                Map<String, String> headers = new LinkedHashMap<>();
                respond.path("headers").fields().forEachRemaining(f -> headers.put(f.getKey(), f.getValue().asText()));
                byte[] body = new byte[0];
                if (respond.has("json")) {
                    headers.putIfAbsent("Content-Type", "application/json");
                    body = JSON.writeValueAsBytes(respond.get("json"));
                } else if (respond.has("body")) {
                    body = respond.get("body").asText().getBytes(StandardCharsets.UTF_8);
                }
                target.respond(new MockTarget.Response(respond.path("status").asInt(200), headers, body, 0));
            }
            int before = target.received.size();
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = JSON.convertValue(mcp.at("/params/arguments"), Map.class);
            McpSchema.CallToolResult result = server.call(null, new McpSchema.CallToolRequest(mcp.at("/params/name").asText(),
                    arguments == null ? Map.of() : arguments, null));

            JsonNode upstream = step.path("upstream");
            if (upstream.isTextual() && upstream.asText().equals("none")) {
                assertThat(target.received.size()).as("no upstream request").isEqualTo(before);
            } else if (upstream.isObject()) {
                assertThat(target.received.size()).as("one upstream request").isEqualTo(before + 1);
                checkRequest(upstream, target.last());
            }
            checkResult(step.path("expect"), result);
        }

        private static void checkRequest(JsonNode want, MockTarget.Received got) throws IOException {
            assertThat(got.method()).as("method").isEqualTo(want.path("method").asText());
            assertThat(got.path()).as("path").isEqualTo(want.path("path").asText());
            want.path("query").fields().forEachRemaining(f -> matches("query " + f.getKey(), queryValue(got.query(), f.getKey()), f.getValue()));
            want.path("headers").fields().forEachRemaining(f -> matches("header " + f.getKey(), got.header(f.getKey()), f.getValue()));
            if (want.has("json")) {
                assertThat(JSON.readTree(got.body())).as("JSON body").isEqualTo(want.get("json"));
            }
            if (want.has("parts")) {
                List<MockTarget.Part> parts = got.parts();
                assertThat(parts).as("parts").hasSize(want.get("parts").size());
                for (int i = 0; i < parts.size(); i++) {
                    JsonNode w = want.get("parts").get(i);
                    MockTarget.Part part = parts.get(i);
                    String disposition = part.header("Content-Disposition");
                    assertThat(disposition).as("part %d name", i).contains("name=\"" + w.path("name").asText() + "\"");
                    if (w.has("contentType")) {
                        assertThat(part.header("Content-Type")).as("part %s content type", w.path("name").asText()).isEqualTo(w.get("contentType").asText());
                    }
                    if (w.has("filename")) {
                        assertThat(disposition).as("part filename").contains("filename=\"" + w.get("filename").asText() + "\"");
                    }
                    if (w.has("json")) {
                        assertThat(JSON.readTree(part.body())).as("part %s JSON", w.path("name").asText()).isEqualTo(w.get("json"));
                    }
                    if (w.has("sizeBytes")) {
                        assertThat(part.body().length).as("part %s size", w.path("name").asText()).isEqualTo(w.get("sizeBytes").asInt());
                    }
                }
            }
        }

        private static void checkResult(JsonNode expect, McpSchema.CallToolResult result) {
            JsonNode structured = JSON.valueToTree(result.structuredContent());
            if (expect.has("isError")) {
                assertThat(Boolean.TRUE.equals(result.isError())).as("isError (%s)", result.content()).isEqualTo(expect.get("isError").asBoolean());
            }
            if (expect.has("code")) {
                assertThat(structured.path("code").asText()).as("error code").isEqualTo(expect.get("code").asText());
            }
            if (expect.has("structuredContent")) {
                subset("structuredContent", structured, expect.get("structuredContent"));
            }
            if (expect.has("meta")) {
                subset("_meta", JSON.valueToTree(result.meta()), expect.get("meta"));
            }
        }

        /** Every key in {@code want} must match {@code got}; extra keys in {@code got} are fine. */
        private static void subset(String what, JsonNode got, JsonNode want) {
            Iterator<Map.Entry<String, JsonNode>> fields = want.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                JsonNode value = got == null ? null : got.get(f.getKey());
                matches(what + "." + f.getKey(), value == null || value.isNull() ? null : value.isValueNode() ? value.asText() : value.toString(), f.getValue());
            }
        }

        /** Matchers: exact, {startsWith}, {matches}, {absent: true}, {any: true}. */
        private static void matches(String what, String actual, JsonNode want) {
            if (want.isObject() && want.has("absent")) {
                assertThat(actual).as(what).isNull();
            } else if (want.isObject() && want.has("any")) {
                assertThat(actual).as(what).isNotNull();
            } else if (want.isObject() && want.has("startsWith")) {
                assertThat(actual).as(what).startsWith(want.get("startsWith").asText());
            } else if (want.isObject() && want.has("matches")) {
                assertThat(actual).as(what).matches(want.get("matches").asText());
            } else if (want.isValueNode()) {
                assertThat(actual).as(what).isEqualTo(want.asText());
            } else {
                try {
                    assertThat(actual == null ? null : JSON.readTree(actual)).as(what).isEqualTo(want);
                } catch (IOException e) {
                    fail(what + ": expected " + want + " but was " + actual);
                }
            }
        }

        private static String queryValue(String query, String name) {
            if (query == null) {
                return null;
            }
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8).equals(name)) {
                    return kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
                }
            }
            return null;
        }

        @Override
        public void close() {
            if (target != null) {
                target.close();
            }
        }
    }
}
