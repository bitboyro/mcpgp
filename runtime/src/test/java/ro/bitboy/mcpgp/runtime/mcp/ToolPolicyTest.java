package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.call.MockTarget;
import ro.bitboy.mcpgp.runtime.config.ConfigException;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.config.ToolPolicy;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class ToolPolicyTest {
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();
    static final Manifest ORDERS = Manifest.file(REPO.resolve("examples/manifest/orders-mcp.manifest.json"));
    static final String GET = "orders_customer_get";
    @TempDir Path dir;
    MockTarget target;

    @BeforeEach void start() throws Exception { target = new MockTarget(); }
    @AfterEach void stop() { target.close(); }

    RuntimeConfig config(Manifest manifest, String yaml, Map<String, String> env, Map<String, String> flags) throws Exception {
        Path file = dir.resolve("server.yaml");
        Files.writeString(file, yaml);
        return new ConfigLoader(new Schemas()).load(manifest,
                new ConfigLoader.Options(Optional.of(file), dir, env, flags));
    }

    McpServerFactory server(String policy, String first, String second) throws Exception {
        return new McpServerFactory(ORDERS, config(ORDERS, policy + """
                instances:
                  - id: production
                    baseUrl: "%s"
                    contract: "2.0"
                %s
                  - id: staging
                    baseUrl: "%s"
                    contract: "2.0"
                %s
                """.formatted(target.address(), first, target.address(), second), Map.of(), Map.of("storage.root", dir.resolve("data").toString())));
    }

    McpSchema.CallToolResult call(McpServerFactory server, String name, Map<String, Object> args) {
        return server.call(null, new McpSchema.CallToolRequest(name, args));
    }

    @Test void absentAllowInheritsButEmptyAllowDisables() throws Exception {
        McpServerFactory inherited = server("tools: { allow: [orders_customer_get] }\n", "", "    tools: { allow: [] }");
        assertThat(inherited.catalog().tool(GET)).isPresent();
        assertThat(inherited.catalog().tool(GET).orElseThrow().at("/inputSchema/properties/instance/enum").toString()).isEqualTo("[\"production\"]");
        McpServerFactory blank = server("tools: { allow: [] }\n", "", "");
        assertThat(blank.catalog().all().values()).noneMatch(t -> t.path("kind").asText().equals("api"));
        assertThat(call(blank, GET, Map.of("instance", "production", "path", Map.of("id", "1"))).isError()).isTrue();
        assertThat(target.received).isEmpty();
    }

    @Test void childCannotWidenParentAndDenyWins() throws Exception {
        McpServerFactory server = server("tools: { allow: ['orders_customer_*'], deny: [orders_customer_create] }\n",
                "    tools: { allow: ['orders_*'] }", "");
        assertThat(server.catalog().tool(GET)).isPresent();
        assertThat(server.catalog().tool("orders_customer_create")).isEmpty();
        assertThat(server.catalog().tool("orders_order_search")).isEmpty();
    }

    @Test void blockedInstanceCannotBeReachedDirectlyOrThroughDiscovery() throws Exception {
        McpServerFactory server = server("", "    tools: { deny: [orders_customer_get] }", "");
        Map<String, Object> args = Map.of("instance", "production", "path", Map.of("id", "1"));
        assertThat(call(server, GET, args).isError()).isTrue();
        assertThat(call(server, "call_tool", Map.of("name", GET, "arguments", args)).isError()).isTrue();
        JsonNode search = JSON.valueToTree(call(server, "search_tools", Map.of("instance", "production", "query", GET)).structuredContent());
        assertThat(search.path("tools").findValuesAsText("name")).doesNotContain(GET);
        assertThat(call(server, "describe_tool", Map.of("name", GET, "instance", "production")).isError()).isTrue();
        assertThat(target.received).isEmpty();
        assertThat(call(server, GET, Map.of("instance", "staging", "path", Map.of("id", "1"))).isError()).isFalse();
        assertThat(target.received).hasSize(1);
    }

    @Test void invalidExactNameRejectsButUnmatchedGlobWarns() throws Exception {
        String instances = "instances: [{id: production, baseUrl: localhost, contract: '2.0'}]\n";
        assertThatThrownBy(() -> config(ORDERS, instances + "tools: {allow: [orders_typo]}\n", Map.of(), Map.of()))
                .isInstanceOf(ConfigException.class).hasMessageContaining("/tools/allow").hasMessageContaining("orders_typo");
        RuntimeConfig loaded = config(ORDERS, instances + "tools: {allow: ['missing_*']}\n", Map.of(), Map.of());
        assertThat(loaded.warnings()).anyMatch(w -> w.contains("missing_*") && w.contains("/tools/allow"));
        assertThat(new ToolCatalog(ORDERS, loaded).all().values()).noneMatch(t -> t.path("kind").asText().equals("api"));
    }

    @Test void environmentAndCliSupportIndexedAndEmptyAllowLists() throws Exception {
        String yaml = "instances: [{id: production, baseUrl: localhost, contract: '2.0'}]\ntools: {allow: [orders_customer_get]}\n";
        RuntimeConfig indexed = config(ORDERS, yaml, Map.of("ORDERS_MCP_TOOLS__ALLOW_0", "orders_order_search"), Map.of());
        assertThat(new ToolCatalog(ORDERS, indexed).tool(GET)).isEmpty();
        assertThat(new ToolCatalog(ORDERS, indexed).tool("orders_order_search")).isPresent();
        RuntimeConfig empty = config(ORDERS, yaml, Map.of("ORDERS_MCP_TOOLS__ALLOW", "[]"), Map.of());
        assertThat(new ToolCatalog(ORDERS, empty).tool(GET)).isEmpty();
        RuntimeConfig flags = config(ORDERS, yaml, Map.of("ORDERS_MCP_TOOLS__ALLOW", "[]"), Map.of("tools.allow", "[\"orders_customer_get\"]"));
        assertThat(new ToolCatalog(ORDERS, flags).tool(GET)).isPresent();
    }

    @Test void apiAndInstancePoliciesAreIndependentEvenWhenIdsMatch() throws Exception {
        Manifest manifest = Manifest.file(REPO.resolve("examples/manifest/ops-assistant.manifest.json"));
        RuntimeConfig loaded = config(manifest, """
                tools: {allow: ['*']}
                apis:
                  orders:
                    tools: {deny: [orders_customer_create]}
                    instances: [{id: prod, baseUrl: localhost, tools: {allow: ['orders_*']}}]
                  tickets:
                    tools: {allow: ['tickets_*']}
                    instances: [{id: prod, baseUrl: localhost}]
                  monitoring:
                    instances: [{id: central, baseUrl: localhost}]
                """, Map.of(), Map.of());
        ToolPolicy policy = new ToolPolicy(manifest, loaded);
        assertThat(policy.deniedBy(manifest.tools().get("orders_customer_create"), loaded.instances().get("orders").getFirst())).contains("/apis/orders/tools");
        assertThat(policy.allows(manifest.tools().get("tickets_ticket_list"), loaded.instances().get("tickets").getFirst())).isTrue();
    }

    @Test void namespacedToolsUseFinalNamesAndCannotReachOtherInstances() throws Exception {
        ObjectNode root = ORDERS.root().deepCopy();
        ((ObjectNode) root.path("instances")).put("mixedContracts", "namespaced");
        Manifest manifest = Manifest.of(root);
        RuntimeConfig loaded = config(manifest, """
                tools: {allow: [production__orders_customer_get]}
                instances:
                  - {id: production, baseUrl: "%s", contract: "2.0"}
                  - {id: staging, baseUrl: "%s", contract: "2.0"}
                """.formatted(target.address(), target.address()), Map.of(), Map.of("storage.root", dir.resolve("data").toString()));
        McpServerFactory server = new McpServerFactory(manifest, loaded);
        assertThat(server.catalog().tool("production__" + GET)).isPresent();
        assertThat(server.catalog().tool("staging__" + GET)).isEmpty();
        assertThat(call(server, "production__" + GET, Map.of("path", Map.of("id", "1"))).isError()).isFalse();
        assertThat(call(server, "call_tool", Map.of("name", "production__" + GET, "arguments", Map.of("path", Map.of("id", "1")))).isError()).isFalse();
        assertThat(target.received).hasSize(2);
    }

    @Test void aDeniedInstanceCannotSupplyCredentialsForAListedTool() throws Exception {
        ObjectNode root = ORDERS.root().deepCopy();
        for (JsonNode tool : root.path("tools")) {
            if (tool.path("name").asText().equals(GET)) {
                for (JsonNode variant : tool.path("variants")) {
                    ((ObjectNode) variant).set("security", JSON.readTree("[[{\"scheme\":\"basicAuth\"}]]"));
                }
            }
        }
        Manifest manifest = Manifest.of(root);
        RuntimeConfig loaded = config(manifest, """
                instances:
                  - id: production
                    baseUrl: localhost
                    contract: "2.0"
                    tools: {allow: []}
                    auth: {type: basic, username: user, password: "${env:SECRET}"}
                  - {id: staging, baseUrl: localhost, contract: "2.0", auth: {type: apiKey, value: "${env:SECRET}"}}
                """, Map.of("SECRET", "password"), Map.of());
        assertThat(new ToolCatalog(manifest, loaded).tool(GET)).isEmpty();
    }

    @Test void toolsetActivationDoesNotRestoreDeniedTools() throws Exception {
        ObjectNode root = ORDERS.root().deepCopy();
        ObjectNode exposure = root.putObject("exposure").put("mode", "toolsets");
        exposure.putObject("toolsets").putObject("customers").putArray("tools").add(GET);
        Manifest manifest = Manifest.of(root);
        RuntimeConfig loaded = config(manifest, "tools: {allow: []}\ninstances: [{id: prod, baseUrl: localhost, contract: '2.0'}]", Map.of(), Map.of());
        ToolCatalog catalog = new ToolCatalog(manifest, loaded);
        Toolsets sets = Toolsets.of(manifest);
        assertThatThrownBy(() -> sets.handlers(catalog).get("toolset_enable").call(null, JSON.createObjectNode(), Map.of("name", "customers"), new CallRecord()))
                .isInstanceOf(ToolError.class);
        assertThat(sets.visible(null)).isEmpty();
    }
}
