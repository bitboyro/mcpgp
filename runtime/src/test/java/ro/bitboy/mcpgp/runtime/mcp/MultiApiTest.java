package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.call.MockTarget;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A server combining three APIs, end to end (requirements: "server can combine several APIs"): the
 * ops-assistant example, each API at its own target server with its own sign-in, and an instance id
 * ("prod") that two APIs share.
 */
class MultiApiTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Manifest OPS = Manifest.file(REPO.resolve("examples/manifest/ops-assistant.manifest.json"));
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;
    MockTarget orders;
    MockTarget tickets;
    MockTarget monitoring;
    McpServerFactory server;

    @BeforeEach
    void start() throws Exception {
        orders = new MockTarget();
        tickets = new MockTarget();
        monitoring = new MockTarget();
        Path file = dir.resolve("ops-assistant.yaml");
        Files.writeString(file, """
                apis:
                  orders:
                    instances:
                      - { id: prod, baseUrl: "%s", auth: { type: basic, username: ops, password: "${env:ORDERS_PASSWORD}" } }
                      - { id: test, baseUrl: "%1$s", readOnly: true, auth: { type: basic, username: ops, password: "${env:ORDERS_PASSWORD}" } }
                  tickets:
                    instances:
                      - { id: prod, baseUrl: "%s", auth: { type: bearer, token: "${env:TICKETS_TOKEN}" } }
                  monitoring:
                    instances:
                      - id: central
                        baseUrl: "%s"
                        auth: { type: oauth2ClientCredentials, tokenUrl: "%3$s/token", clientId: ops, clientSecret: "${env:MONITORING_SECRET}" }
                """.formatted(orders.address(), tickets.address(), monitoring.address()));
        RuntimeConfig config = new ConfigLoader(new Schemas()).load(OPS, new ConfigLoader.Options(Optional.of(file), dir,
                Map.of("ORDERS_PASSWORD", "orders-secret", "TICKETS_TOKEN", "tickets-token", "MONITORING_SECRET", "monitoring-secret"),
                Map.of("storage.root", dir.resolve("data").toString())));
        server = new McpServerFactory(OPS, config);
    }

    @AfterEach
    void stop() {
        orders.close();
        tickets.close();
        monitoring.close();
    }

    JsonNode structured(String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = server.call(null, new McpSchema.CallToolRequest(tool, arguments, null));
        assertThat(result.isError()).as("%s: %s", tool, result.content()).isFalse();
        return JSON.valueToTree(result.structuredContent());
    }

    McpSchema.CallToolResult call(String tool, String instance, Map<String, Object> arguments) {
        return server.call(null, new McpSchema.CallToolRequest("call_tool", Map.of("instance", instance, "name", tool, "arguments", arguments), null));
    }

    @Test
    void eachApiListsItsOwnInstances() {
        assertThat(structured("orders_instances_list", Map.of()).path("instances").findValuesAsText("name")).containsExactly("prod", "test");
        assertThat(structured("tickets_instances_list", Map.of()).path("instances").findValuesAsText("name")).containsExactly("prod");
        assertThat(structured("monitoring_instances_list", Map.of()).path("instances").findValuesAsText("name")).containsExactly("central");
        // a direct tool only offers its own API's instances
        JsonNode described = structured("describe_tool", Map.of("instance", "central", "name", "monitoring_alert_list"));
        assertThat(described.at("/inputSchema/properties/instance/enum")).extracting(JsonNode::asText).containsExactly("central");
    }

    @Test
    void callsGoToTheirApisTargetWithItsOwnSignIn() throws Exception {
        orders.respond(MockTarget.Response.json(200, "{\"id\":\"o-1\"}"));
        assertThat(call("orders_order_search", "prod", Map.of()).isError()).isFalse();
        assertThat(orders.last().path()).startsWith("/api/v2/orders");
        assertThat(orders.last().header("Authorization"))
                .isEqualTo("Basic " + Base64.getEncoder().encodeToString("ops:orders-secret".getBytes(StandardCharsets.UTF_8)));

        tickets.respond(MockTarget.Response.json(200, "{\"id\":\"t-7\",\"title\":\"Refund stuck\"}"));
        McpSchema.CallToolResult ticket = call("tickets_ticket_get", "prod", Map.of("path", Map.of("id", "t-7")));
        assertThat(ticket.isError()).as("%s", ticket.content()).isFalse();
        assertThat(tickets.last().path()).isEqualTo("/api/3/tickets/t-7");
        assertThat(tickets.last().header("Authorization")).isEqualTo("Bearer tickets-token");
        // the same id in two APIs: each call reached only its own API
        assertThat(orders.received).hasSize(1);

        monitoring.respond(MockTarget.Response.json(200, "{\"access_token\":\"mon-at\",\"token_type\":\"Bearer\",\"expires_in\":300}"));
        monitoring.respond(MockTarget.Response.json(200, "[{\"id\":\"a-1\",\"state\":\"firing\"}]"));
        assertThat(call("monitoring_alert_list", "central", Map.of()).isError()).isFalse();
        assertThat(monitoring.received.get(0).path()).isEqualTo("/token");
        assertThat(monitoring.last().path()).isEqualTo("/v1/alerts");
        assertThat(monitoring.last().header("Authorization")).isEqualTo("Bearer mon-at");
    }

    @Test
    void anInstanceOfAnotherApiIsRefused() {
        McpSchema.CallToolResult wrong = call("monitoring_alert_list", "prod", Map.of());
        assertThat(wrong.isError()).isTrue();
        assertThat(monitoring.received).isEmpty();
        assertThat(orders.received).isEmpty();
        assertThat(tickets.received).isEmpty();
    }

    @Test
    void searchSpansEveryApi() {
        List<String> tools = structured("search_tools", Map.of("instance", "prod", "query", "ticket comment")).path("tools").findValuesAsText("name");
        assertThat(tools).contains("tickets_ticket_comment_create");
        List<String> alerts = structured("search_tools", Map.of("instance", "central", "query", "alerts firing")).path("tools").findValuesAsText("name");
        assertThat(alerts.get(0)).startsWith("monitoring_alert");
    }

    @Test
    void readOnlyInstancesAndTheAuditApplyAcrossApis() {
        McpSchema.CallToolResult write = call("orders_customer_create", "test", Map.of("body", Map.of("name", "x", "accountId", "a")));
        assertThat(write.isError()).isTrue();
        assertThat(orders.received).isEmpty();
        tickets.respond(MockTarget.Response.json(200, "[]"));
        call("tickets_ticket_list", "prod", Map.of());
        JsonNode audit = structured("audit_list", Map.of());
        assertThat(audit.path("items").findValuesAsText("tool")).contains("tickets_ticket_list", "orders_customer_create");
    }
}
