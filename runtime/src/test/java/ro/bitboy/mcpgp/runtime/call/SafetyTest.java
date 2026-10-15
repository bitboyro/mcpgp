package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.mcp.McpServerFactory;
import ro.bitboy.mcpgp.runtime.mcp.ToolError;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The security and safety settings (definition safety, on by default): sizes, rate limits, parameter encoding. */
class SafetyTest {

    static final Path ORDERS = Path.of("..").toAbsolutePath().normalize().resolve("examples/manifest/orders-mcp.manifest.json");
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;
    MockTarget target;

    @BeforeEach
    void start() throws IOException {
        target = new MockTarget();
    }

    @AfterEach
    void stop() {
        target.close();
    }

    McpServerFactory server(Consumer<ObjectNode> policies, String instanceExtra) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(ORDERS.toFile());
        policies.accept((ObjectNode) root.path("policies"));
        Path file = dir.resolve("orders-mcp.yaml");
        Files.writeString(file, "instances:\n  - { id: prod, baseUrl: \"" + target.address() + "\", contract: \"2.0\"" + instanceExtra + " }\n");
        Manifest manifest = Manifest.of(root);
        RuntimeConfig config = new ConfigLoader(new Schemas()).load(manifest, new ConfigLoader.Options(Optional.of(file), dir,
                Map.of(), Map.of("storage.root", dir.resolve("data").toString())));
        return new McpServerFactory(manifest, config);
    }

    static McpSchema.CallToolResult list(McpServerFactory server, Map<String, Object> query) {
        Map<String, Object> all = new java.util.LinkedHashMap<>(query);
        all.put("accountId", "a-1");
        return server.call(null, new McpSchema.CallToolRequest("orders_customer_list", Map.of("instance", "prod", "query", all), null));
    }

    static JsonNode structured(McpSchema.CallToolResult result) {
        return JSON.valueToTree(result.structuredContent());
    }

    static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    static String customers(int count) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(Map.of("id", "c-" + i, "name", "Customer number " + i, "email", "customer" + i + "@example.com"));
        }
        try {
            return JSON.writeValueAsString(Map.of("items", items, "next", "cursor-2"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ outputs

    @Test
    void aLargeJsonAnswerIsCutAtWholeItemsAndSaysHowManyWereLeftOut() throws IOException {
        McpServerFactory server = server(p -> p.put("responseLimitKb", 16), "");
        target.respond(MockTarget.Response.json(200, customers(2000)));

        McpSchema.CallToolResult result = list(server, Map.of());

        assertThat(result.isError()).as(text(result)).isFalse();
        JsonNode items = structured(result).path("items");
        assertThat(items.size()).isBetween(50, 1999);
        assertThat(items.get(items.size() - 1).path("email").asText()).as("the last item is whole").endsWith("@example.com");
        assertThat(structured(result).path("next").asText()).as("the rest of the answer stays").isEqualTo("cursor-2");
        assertThat(result.meta()).containsKey("mcpgp/truncated");
        assertThat(text(result)).contains(items.size() + " of 2000 items of /items shown");
    }

    @Test
    void withCleanTruncationOffTheAnswerIsCutAsText() throws IOException {
        McpServerFactory server = server(p -> p.put("responseLimitKb", 16).put("cleanTruncation", false), "");
        target.respond(MockTarget.Response.json(200, customers(2000)));

        McpSchema.CallToolResult result = list(server, Map.of());

        assertThat(result.structuredContent()).isNull();
        assertThat(text(result)).contains("[truncated: 16 KB of");
    }

    @Test
    void aHugeAnswerIsNeverReadWhole() throws IOException {
        McpServerFactory server = server(p -> p.put("responseLimitKb", 16), "");   // reads at most 1 MB
        target.respond(MockTarget.Response.json(200, customers(60_000)));        // about 5 MB

        McpSchema.CallToolResult result = list(server, Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("over the 16 KB limit").contains("narrow the query");
        assertThat(text(result).length()).isLessThan(20 * 1024);
    }

    @Test
    void aTargetsRetryAfterReachesTheAgent() throws IOException {
        McpServerFactory server = server(p -> { }, "");
        target.respond(new MockTarget.Response(429, Map.of("Retry-After", "7"), new byte[0], 0));

        McpSchema.CallToolResult result = list(server, Map.of());

        assertThat(structured(result).path("code").asText()).isEqualTo("UPSTREAM_4XX");
        assertThat(result.meta()).containsEntry("mcpgp/retryAfterSeconds", 7L);
        assertThat(text(result)).contains("wait 7 s");
    }

    // ------------------------------------------------------------------ inputs

    @Test
    void textLongerThanTheLimitIsRefusedBeforeAnythingIsSent() throws IOException {
        McpServerFactory server = server(p -> { }, "");

        McpSchema.CallToolResult result = list(server, Map.of("cursor", "x".repeat(70_000)));

        assertThat(structured(result).path("code").asText()).isEqualTo("INPUT_TOO_LARGE");
        assertThat(text(result)).contains("query.cursor is 70000 characters; the limit is 65536");
        assertThat(target.received).isEmpty();
    }

    @Test
    void argumentsOverTheRequestLimitAreRefused() throws IOException {
        McpServerFactory server = server(p -> p.putObject("inputLimits").put("enabled", true).put("requestKb", 1).put("stringMaxLength", 65536), "");

        McpSchema.CallToolResult result = list(server, Map.of("cursor", "x".repeat(2000)));

        assertThat(structured(result).path("code").asText()).isEqualTo("INPUT_TOO_LARGE");
        assertThat(text(result)).contains("the limit is 1 KB");
    }

    @Test
    void inputLimitsCanBeTurnedOff() throws IOException {
        McpServerFactory server = server(p -> p.putObject("inputLimits").put("enabled", false), "");

        McpSchema.CallToolResult result = list(server, Map.of("cursor", "x".repeat(70_000)));

        assertThat(result.isError()).as(text(result)).isFalse();
    }

    @Test
    void parametersCantAddCookiesOrHeaders() {
        assertThat(RequestBuilder.encodeCookie("abc; admin=true")).isEqualTo("abc%3B%20admin=true");
        assertThat(RequestBuilder.encodeCookie("a,b\"c\\d é")).isEqualTo("a%2Cb%22c%5Cd%20%C3%A9");
        assertThat(RequestBuilder.encodeCookie("plain-value_1.2")).isEqualTo("plain-value_1.2");
        assertThatThrownBy(() -> RequestBuilder.headerValue("X-Trace", "abc\r\nX-Admin: true"))
                .isInstanceOf(ToolError.class).hasMessageContaining("header.X-Trace may not contain line breaks");
        assertThat(RequestBuilder.headerValue("X-Trace", "a\tb")).isEqualTo("a\tb");
    }

    // ------------------------------------------------------------------ rate limits

    @Test
    void callsPerMinuteAreLimitedPerTargetAndTheAgentIsToldHowLongToWait() throws IOException {
        McpServerFactory server = server(p -> p.putObject("rateLimits").put("enabled", true).put("concurrent", 8).put("perMinute", 2)
                .put("retryAfter", true), "");

        assertThat(list(server, Map.of()).isError()).isFalse();
        assertThat(list(server, Map.of()).isError()).isFalse();
        McpSchema.CallToolResult third = list(server, Map.of());

        assertThat(structured(third).path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(third.meta()).containsKey("mcpgp/retryAfterSeconds").containsEntry("mcpgp/instance", "prod");
        assertThat(target.received).as("the third call isn't sent").hasSize(2);
    }

    @Test
    void callsOverTheConcurrencyLimitWaitForAFreeSlot() throws Exception {
        McpServerFactory server = server(p -> p.putObject("rateLimits").put("enabled", true).put("concurrent", 1).put("perMinute", 100)
                .put("retryAfter", true), "");
        for (int i = 0; i < 3; i++) {
            target.respond(new MockTarget.Response(200, Map.of("Content-Type", "application/json"), "{}".getBytes(StandardCharsets.UTF_8), 300));
        }

        long start = System.nanoTime();
        List<CompletableFuture<McpSchema.CallToolResult>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> list(server, Map.of())));
        }
        for (CompletableFuture<McpSchema.CallToolResult> call : calls) {
            assertThat(call.join().isError()).isFalse();
        }
        assertThat((System.nanoTime() - start) / 1_000_000).as("one after another").isGreaterThanOrEqualTo(850);
    }

    @Test
    void anOperatorCanChangeTheLimitsForTheirTarget() throws IOException {
        McpServerFactory server = server(p -> p.putObject("rateLimits").put("enabled", true).put("concurrent", 8).put("perMinute", 1)
                .put("retryAfter", true), ", rateLimits: { perMinute: 5 }");

        for (int i = 0; i < 5; i++) {
            assertThat(list(server, Map.of()).isError()).isFalse();
        }
        assertThat(structured(list(server, Map.of())).path("code").asText()).isEqualTo("RATE_LIMITED");
    }
}
