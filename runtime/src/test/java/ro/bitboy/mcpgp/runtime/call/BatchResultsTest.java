package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.config.*;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.mcp.*;
import ro.bitboy.mcpgp.schemas.Schemas;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BatchResultsTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;
    MockTarget target;
    McpServerFactory server;
    Manifest manifest;
    RuntimeConfig runtime;
    @BeforeEach void start() throws Exception {
        target = new MockTarget();
        ObjectNode root = (ObjectNode) JSON.readTree(Path.of("../examples/manifest/orders-mcp.manifest.json").toFile());
        root.withObject("/policies").put("responseLimitKb", 1);
        root.withObject("/policies/batch").put("enabled", true).put("allowWrites", true).put("maxCalls", 10).put("concurrency", 2).put("deduplicateRequests", true);
        root.withObject("/policies/results").put("mode", "auto").put("captureLimitKb", 8).put("maxStoredKb", 64);
        for (String name : List.of("batch_call", "result_get")) {
            root.withArray("tools").addObject().put("name", name).put("kind", "builtin").put("handler", name)
                    .putObject("inputSchema").put("type", "object");
        }
        Path config = dir.resolve("server.yaml");
        Files.writeString(config, "instances:\n  - { id: prod, baseUrl: \"" + target.address() + "\", contract: \"2.0\" }\n");
        manifest = Manifest.of(root);
        runtime = new ConfigLoader(new Schemas()).load(manifest, new ConfigLoader.Options(Optional.of(config), dir,
                Map.of(), Map.of("storage.root", dir.resolve("data").toString())));
        server = new McpServerFactory(manifest, runtime);
    }
    @AfterEach void stop() { target.close(); }
    McpSchema.CallToolResult call(String name, Map<String, Object> args) { return server.call(null, new McpSchema.CallToolRequest(name, args, null)); }
    JsonNode tree(McpSchema.CallToolResult result) { return JSON.valueToTree(result.structuredContent()); }
    Map<String, Object> get(String id) { return Map.of("id", id, "tool", "orders_customer_get", "arguments", Map.of("instance", "prod", "path", Map.of("id", "c-1"))); }
    @Test void discoveryRetainsLargeResponseAndRetrievalNeverRepeatsTheRequest() {
        String body = "{\"items\":[" + String.join(",", Collections.nCopies(40, "{\"id\":\"c-1\",\"name\":\"" + "x".repeat(40) + "\"}")) + "]}";
        target.respond(MockTarget.Response.json(200, body));
        var result = call("call_tool", Map.of("name", "orders_customer_get", "instance", "prod", "arguments", Map.of("path", Map.of("id", "c-1"))));
        assertThat(result.isError()).isFalse();
        String id = tree(result).path("resultId").asText(); assertThat(id).startsWith("r_");
        int requests = target.received.size();
        JsonNode page = tree(call("result_get", Map.of("resultId", id, "pointer", "/items", "limit", 2)));
        assertThat(page.path("items").size()).isEqualTo(2);
        assertThat(page.path("nextOffset").asInt()).isEqualTo(2);
        assertThat(target.received).hasSize(requests);
        assertThat(tree(call("result_get", Map.of("resultId", "missing"))).path("code").asText()).isEqualTo("RESULT_NOT_FOUND");
    }
    @Test void invalidLaterEntryPreventsAllRequests() {
        var bad = Map.of("id", "bad", "tool", "orders_customer_get", "arguments", Map.of("instance", "prod", "path", Map.of()));
        var result = call("batch_call", Map.of("calls", List.of(get("first"), bad)));
        assertThat(tree(result).path("code").asText()).isEqualTo("INPUT_VALIDATION");
        assertThat(target.received).isEmpty();
    }
    @Test void sequentialFailureStopsRemainingCalls() {
        target.respond(MockTarget.Response.json(400, "{}"));
        var result = call("batch_call", Map.of("calls", List.of(get("a"), get("b"))));
        assertThat(tree(result).at("/results/0/state").asText()).isEqualTo("failed");
        assertThat(tree(result).at("/results/1/state").asText()).isEqualTo("not_started");
        assertThat(target.received).hasSize(1);
    }
    @Test void parallelReadsReturnResultsInInputOrder() {
        var result = call("batch_call", Map.of("execution", "parallel", "calls", List.of(get("a"), get("b"))));
        assertThat(tree(result).at("/results/0/id").asText()).isEqualTo("a");
        assertThat(tree(result).at("/results/1/state").asText()).isEqualTo("succeeded");
        assertThat(target.received).hasSize(2);
    }
    @Test void writesRequireExactBatchConfirmationAndCannotRunInParallel() {
        ObjectNode tool = (ObjectNode)server.catalog().tool("orders_customer_update").orElseThrow();
        tool.put("confirm", true);
        var write = Map.of("id", "write", "tool", "orders_customer_update", "arguments", Map.of("instance", "prod", "path", Map.of("id", "c-1"), "body", Map.of("name", "Acme", "accountId", "a-1")));
        var calls = List.of(get("read"), write);
        var parallel = call("batch_call", Map.of("execution", "parallel", "calls", calls));
        assertThat(tree(parallel).path("code").asText()).isEqualTo("INPUT_VALIDATION");
        var first = call("batch_call", Map.of("calls", calls));
        assertThat(tree(first).path("code").asText()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(target.received).isEmpty();
        String token = tree(first).path("confirm").asText();
        var changed = call("batch_call", Map.of("calls", List.of(write), "confirm", token));
        assertThat(tree(changed).path("code").asText()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(target.received).isEmpty();
        var approved = call("batch_call", Map.of("calls", List.of(write), "confirm", tree(changed).path("confirm").asText()));
        assertThat(tree(approved).at("/results/0/state").asText()).isEqualTo("succeeded");
        assertThat(target.received).hasSize(1);
        assertThat(target.last().method()).isEqualTo("PUT");
    }
    @Test void postExecutesOnceAndLaterFailureDoesNotUndoIt() {
        var create = Map.of("id", "create", "tool", "orders_customer_create", "arguments", Map.of("instance", "prod", "body", Map.of("name", "Acme", "accountId", "a-1")));
        target.respond(MockTarget.Response.json(201, "{\"id\":\"new\"}"));
        target.respond(MockTarget.Response.json(500, "{}"));
        var result = call("batch_call", Map.of("calls", List.of(create, get("read"), get("skipped"))));
        assertThat(tree(result).at("/results/0/state").asText()).isEqualTo("succeeded");
        assertThat(tree(result).at("/results/1/state").asText()).isEqualTo("failed");
        assertThat(tree(result).at("/results/2/state").asText()).isEqualTo("not_started");
        assertThat(target.received).hasSize(2);
        assertThat(target.received.getFirst().method()).isEqualTo("POST");
    }
    @Test void successfulPostWithOversizedOutputIsNotReportedAsAFailedWrite() {
        var create = Map.of("id", "create", "tool", "orders_customer_create", "arguments", Map.of("instance", "prod", "body", Map.of("name", "Acme", "accountId", "a-1")));
        target.respond(MockTarget.Response.json(201, "{\"data\":\"" + "x".repeat(20000) + "\"}"));
        var result = call("batch_call", Map.of("calls", List.of(create, get("skipped"))));
        assertThat(tree(result).at("/results/0/state").asText()).isEqualTo("succeeded");
        assertThat(tree(result).at("/results/0/outputError").asBoolean()).isTrue();
        assertThat(tree(result).at("/results/1/state").asText()).isEqualTo("not_started");
        assertThat(target.received).hasSize(1);
    }
    @Test void captureLimitDoesNotPromiseFullRetrieval() {
        target.respond(MockTarget.Response.json(200, "{\"value\":\"" + "x".repeat(20000) + "\"}"));
        var result = call("orders_customer_get", Map.of("instance", "prod", "path", Map.of("id", "c-1")));
        assertThat(tree(result).has("resultId")).isFalse();
        assertThat(ResultsSize(result)).isLessThan(1024);
    }
    @Test void requestIdSurvivesRestartAndPreventsARepeatedWrite() {
        var create = Map.of("id", "create", "tool", "orders_customer_create", "arguments", Map.of("instance", "prod", "body", Map.of("name", "Acme", "accountId", "a-1")));
        target.respond(MockTarget.Response.json(201, "{\"id\":\"new\"}"));
        var args = Map.<String, Object>of("requestId", "write-1", "calls", List.of(create));
        var first = call("batch_call", args);
        assertThat(tree(first).path("requestId").asText()).isEqualTo("write-1");
        server = new McpServerFactory(manifest, runtime);
        var replay = call("batch_call", args);
        assertThat(tree(replay).path("deduplicated").asBoolean()).isTrue();
        assertThat(target.received).hasSize(1);
        assertThat(target.last().method()).isEqualTo("POST");
    }
    @Test void reusedRequestIdWithDifferentBatchIsRejectedWithoutCallingTheTarget() {
        target.respond(MockTarget.Response.json(200, "{}"));
        call("batch_call", Map.of("requestId", "read-1", "calls", List.of(get("one"))));
        var result = call("batch_call", Map.of("requestId", "read-1", "calls", List.of(get("two"))));
        assertThat(tree(result).path("code").asText()).isEqualTo("BATCH_REQUEST_ID_REUSE");
        assertThat(target.received).hasSize(1);
    }
    private int ResultsSize(McpSchema.CallToolResult result) { return ((McpSchema.TextContent)result.content().getFirst()).text().length(); }
}
