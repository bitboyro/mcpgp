package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.mcp.McpServerFactory;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** API tool calls against a mock target, using the orders example manifest (multi-instance, 2 contracts). */
class ApiCallTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Manifest ORDERS = Manifest.file(REPO.resolve("examples/manifest/orders-mcp.manifest.json"));
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;
    MockTarget target;
    McpServerFactory server;

    @BeforeEach
    void start() throws IOException {
        target = new MockTarget();
        configure("");
    }

    @AfterEach
    void stop() {
        target.close();
    }

    void configure(String extra) throws IOException {
        Path file = dir.resolve("orders-mcp.yaml");
        Files.writeString(file, """
                instances:
                  - { id: prod-eu, baseUrl: "%1$s", auth: { type: basic, username: svc, password: "${env:PASS}" } }
                  - { id: partner-x, baseUrl: "%1$s", contract: "1.0", readOnly: true, auth: { type: apiKey, value: "${env:KEY}" } }
                  - { id: slow, baseUrl: "%1$s", timeoutSeconds: 1 }
                """.formatted(target.address()) + extra);
        RuntimeConfig config = new ConfigLoader(new Schemas()).load(ORDERS, new ConfigLoader.Options(Optional.of(file), dir,
                Map.of("PASS", "pa55word-secret", "KEY", "key-12345"), Map.of("storage.root", dir.resolve("data").toString())));
        server = new McpServerFactory(ORDERS, config);
    }

    McpSchema.CallToolResult call(String tool, Map<String, Object> arguments) {
        return server.call(null, new McpSchema.CallToolRequest(tool, arguments, null));
    }

    static JsonNode structured(McpSchema.CallToolResult result) {
        return JSON.valueToTree(result.structuredContent());
    }

    static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    @Test
    void getMapsGroupsToTheRequestAndAddsCredentials() {
        target.respond(MockTarget.Response.json(200, "{\"id\":\"c-1\",\"name\":\"Ana\"}"));
        McpSchema.CallToolResult result = call("orders_customer_get",
                Map.of("instance", "prod-eu", "path", Map.of("id", "c 1/2"), "query", Map.of("expand", "orders")));
        assertThat(result.isError()).isFalse();
        assertThat(structured(result).path("name").asText()).isEqualTo("Ana");
        assertThat(result.meta()).containsEntry("mcpgp/instance", "prod-eu").containsEntry("mcpgp/contract", "2.0")
                .containsEntry("mcpgp/status", 200).containsKey("mcpgp/correlationId");

        MockTarget.Received request = target.last();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo("/api/v2/customers/c%201%2F2");   // basePath appended; value encoded
        assertThat(request.query()).isEqualTo("expand=orders");
        assertThat(request.header("Accept")).isEqualTo("application/json, application/problem+json");
        assertThat(request.header("Authorization")).isEqualTo("Basic " + Base64.getEncoder().encodeToString("svc:pa55word-secret".getBytes()));
    }

    @Test
    void pinnedInstanceUsesItsContractAndApiKey() {
        call("orders_customer_get", Map.of("instance", "partner-x", "path", Map.of("id", "c-1")));
        assertThat(target.last().path()).isEqualTo("/api/v1/customers/c-1");
        assertThat(target.last().header("X-Api-Key")).isEqualTo("key-12345");
        assertThat(target.last().header("Accept")).isEqualTo("application/json");
    }

    @Test
    void propertiesOfAnotherContractAreRejected() {
        McpSchema.CallToolResult result = call("orders_customer_get",
                Map.of("instance", "partner-x", "path", Map.of("id", "c-1"), "query", Map.of("expand", "orders")));
        assertThat(result.isError()).isTrue();
        assertThat(structured(result).path("code").asText()).isEqualTo("INPUT_VALIDATION");
        assertThat(text(result)).contains("query.expand is not available on instance partner-x (contract 1.0)");
        assertThat(target.received).isEmpty();
    }

    @Test
    void instanceChecks() {
        assertThat(structured(call("orders_customer_get", Map.of("path", Map.of("id", "1")))).path("code").asText()).isEqualTo("INSTANCE_REQUIRED");
        McpSchema.CallToolResult unknown = call("orders_customer_get", Map.of("instance", "nope", "path", Map.of("id", "1")));
        assertThat(structured(unknown).path("code").asText()).isEqualTo("INSTANCE_UNKNOWN");
        assertThat(text(unknown)).contains("prod-eu, partner-x, slow");
        // read-only instance: write tools are refused
        McpSchema.CallToolResult write = call("orders_customer_create",
                Map.of("instance", "partner-x", "body", Map.of("name", "A", "accountId", "a-1")));
        assertThat(structured(write).path("code").asText()).isEqualTo("NOT_AVAILABLE_ON_INSTANCE");
        // operation only in 2.0
        McpSchema.CallToolResult missing = call("orders_customer_contact_list", Map.of("instance", "partner-x", "path", Map.of("id", "1")));
        assertThat(structured(missing).path("code").asText()).isEqualTo("NOT_AVAILABLE_ON_INSTANCE");
        assertThat(target.received).isEmpty();
    }

    @Test
    void schemaViolationsAreReportedBeforeSending() {
        McpSchema.CallToolResult result = call("orders_customer_create", Map.of("instance", "prod-eu", "body", Map.of("name", "A")));
        assertThat(structured(result).path("code").asText()).isEqualTo("INPUT_VALIDATION");
        assertThat(text(result)).contains("accountId");
        assertThat(call("orders_customer_get", Map.of("instance", "prod-eu", "path", Map.of("id", ".."))).isError()).isTrue();
        assertThat(target.received).isEmpty();
    }

    @Test
    void jsonBodyIsSentAsJson() throws IOException {
        call("orders_order_refund", Map.of("instance", "prod-eu", "path", Map.of("id", "o-1"), "query", Map.of("notify", false),
                "body", Map.of("amount", 25, "reason", "damaged")));
        MockTarget.Received request = target.last();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/api/v2/orders/o-1/refund");
        assertThat(request.query()).isEqualTo("notify=false");
        assertThat(request.header("Content-Type")).isEqualTo("application/json");
        assertThat(JSON.readTree(request.body())).isEqualTo(JSON.readTree("{\"amount\":25,\"reason\":\"damaged\"}"));
    }

    @Test
    void multipartSendsAJsonPartAndAFilePart() throws IOException {
        byte[] pdf = "%PDF-1.4\n".getBytes(StandardCharsets.UTF_8);
        call("orders_customer_create_multipart", Map.of("instance", "prod-eu", "body", Map.of(
                "payload", Map.of("name", "Ana", "accountId", "a-1"),
                "document", Map.of("base64", Base64.getEncoder().encodeToString(pdf), "filename", "c.pdf", "contentType", "application/pdf"))));
        MockTarget.Received request = target.last();
        assertThat(request.header("Content-Type")).startsWith("multipart/form-data; boundary=");
        List<MockTarget.Part> parts = request.parts();
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).header("Content-Disposition")).isEqualTo("form-data; name=\"payload\"");
        assertThat(parts.get(0).header("Content-Type")).isEqualTo("application/json");
        assertThat(JSON.readTree(parts.get(0).body())).isEqualTo(JSON.readTree("{\"name\":\"Ana\",\"accountId\":\"a-1\"}"));
        assertThat(parts.get(1).header("Content-Disposition")).isEqualTo("form-data; name=\"document\"; filename=\"c.pdf\"");
        assertThat(parts.get(1).header("Content-Type")).isEqualTo("application/pdf");
        assertThat(parts.get(1).body()).isEqualTo(pdf);
    }

    @Test
    void fileInputRulesApply() {
        McpSchema.CallToolResult png = call("orders_customer_create_multipart", Map.of("instance", "prod-eu", "body", Map.of(
                "payload", Map.of("name", "Ana", "accountId", "a-1"),
                "document", Map.of("base64", "iVBORw0K", "filename", "c.png", "contentType", "image/png"))));
        assertThat(text(png)).startsWith("INPUT_VALIDATION: body.document.contentType: does not have a value in the enumeration [\"application/pdf\"]")
                .doesNotContain("is not defined in the schema");
        McpSchema.CallToolResult byPath = call("orders_customer_create_multipart", Map.of("instance", "prod-eu", "body", Map.of(
                "payload", Map.of("name", "Ana", "accountId", "a-1"), "document", Map.of("path", "c.pdf"))));
        assertThat(text(byPath)).contains("FILE_FORM_DISABLED");
        McpSchema.CallToolResult bad = call("orders_customer_create_multipart", Map.of("instance", "prod-eu", "body", Map.of(
                "payload", Map.of("name", "Ana", "accountId", "a-1"), "document", Map.of("base64", "@@not base64@@"))));
        assertThat(text(bad)).contains("FILE_INVALID_BASE64");
        assertThat(target.received).isEmpty();
    }

    @Test
    void filesByPathStayInsideTheRoot() throws IOException {
        Path uploads = Files.createDirectories(dir.resolve("uploads"));
        Files.writeString(uploads.resolve("c.pdf"), "%PDF-1.4\n");
        Files.writeString(dir.resolve("outside.pdf"), "secret");
        configure("files: { path: { enabled: true, root: " + uploads + " } }\n");
        Map<String, Object> payload = Map.of("name", "Ana", "accountId", "a-1");
        McpSchema.CallToolResult ok = call("orders_customer_create_multipart", Map.of("instance", "prod-eu", "body", Map.of(
                "payload", payload, "document", Map.of("path", "c.pdf"))));
        assertThat(ok.isError()).isFalse();
        assertThat(target.last().parts().get(1).header("Content-Disposition")).contains("filename=\"c.pdf\"");
        McpSchema.CallToolResult escape = call("orders_customer_create_multipart", Map.of("instance", "prod-eu", "body", Map.of(
                "payload", payload, "document", Map.of("path", "../outside.pdf"))));
        assertThat(text(escape)).contains("FILE_OUTSIDE_ROOT");
    }

    @Test
    void binaryResponsesBecomeFileObjects() {
        byte[] pdf = "%PDF-1.4 invoice".getBytes(StandardCharsets.UTF_8);
        target.respond(new MockTarget.Response(200, Map.of("Content-Type", "application/pdf",
                "Content-Disposition", "attachment; filename=\"inv-7.pdf\""), pdf, 0));
        JsonNode file = structured(call("orders_invoice_document_get", Map.of("instance", "prod-eu", "path", Map.of("id", "7"))));
        assertThat(file.path("filename").asText()).isEqualTo("inv-7.pdf");
        assertThat(file.path("contentType").asText()).isEqualTo("application/pdf");
        assertThat(Base64.getDecoder().decode(file.path("base64").asText())).isEqualTo(pdf);
        assertThat(target.last().header("Accept")).isEqualTo("application/pdf, application/problem+json");
    }

    @Test
    void upstreamErrorsHaveCodesAndRedactedDetail() throws IOException {
        target.respond(new MockTarget.Response(404, Map.of("Content-Type", "application/problem+json"),
                "{\"title\":\"Not found\",\"detail\":\"no customer c-9\",\"debug\":\"pw=pa55word-secret\"}".getBytes(), 0));
        McpSchema.CallToolResult notFound = call("orders_customer_get", Map.of("instance", "prod-eu", "path", Map.of("id", "c-9")));
        assertThat(structured(notFound).path("code").asText()).isEqualTo("UPSTREAM_4XX");
        assertThat(text(notFound)).contains("HTTP 404 from prod-eu").contains("no customer c-9").contains("pw=***")
                .doesNotContain("pa55word-secret");
        assertThat(notFound.meta()).containsEntry("mcpgp/status", 404);

        target.respond(MockTarget.Response.json(401, "{}"));
        assertThat(structured(call("orders_customer_get", Map.of("instance", "prod-eu", "path", Map.of("id", "1")))).path("code").asText())
                .isEqualTo("AUTH_DOWNSTREAM");
        target.respond(MockTarget.Response.json(503, "{\"message\":\"maintenance\"}"));
        assertThat(structured(call("orders_customer_get", Map.of("instance", "prod-eu", "path", Map.of("id", "1")))).path("code").asText())
                .isEqualTo("UPSTREAM_5XX");

        configure("errors: { detail: summary }\n");
        target.respond(new MockTarget.Response(404, Map.of("Content-Type", "application/problem+json"),
                "{\"title\":\"Not found\",\"detail\":\"no customer c-9\",\"debug\":\"lots of internals\"}".getBytes(), 0));
        String summary = text(call("orders_customer_get", Map.of("instance", "prod-eu", "path", Map.of("id", "c-9"))));
        assertThat(summary).contains("Not found (no customer c-9)").doesNotContain("internals");
    }

    @Test
    void timeoutsAndUnreachableTargets() throws IOException {
        target.respond(new MockTarget.Response(200, Map.of(), new byte[0], 2500));
        assertThat(structured(call("orders_customer_get", Map.of("instance", "slow", "path", Map.of("id", "1")))).path("code").asText())
                .isEqualTo("UPSTREAM_TIMEOUT");
        target.close();
        assertThat(structured(call("orders_customer_get", Map.of("instance", "prod-eu", "path", Map.of("id", "1")))).path("code").asText())
                .isEqualTo("UPSTREAM_UNREACHABLE");
    }
}
