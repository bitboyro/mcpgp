package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.config.ConfigException;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.mcp.McpServerFactory;
import ro.bitboy.mcpgp.schemas.Schemas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Several contract versions per API (definition-format §3, runtime-config §4.4), with the orders example:
 * contract 1.0 (Swagger 2.0, base path /api/v1) and 2.0 (OpenAPI 3.1, /api/v2, the default), and a
 * detection rule {@code GET /version}, pattern {@code ^(\d+\.\d+)}. Each instance has its own target, so
 * every request shows which instance and which contract sent it.
 */
class ContractVersionsTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Path ORDERS_FILE = REPO.resolve("examples/manifest/orders-mcp.manifest.json");
    static final Manifest ORDERS = Manifest.file(ORDERS_FILE);
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;
    MockTarget prod;
    MockTarget legacy;

    @BeforeEach
    void targets() throws IOException {
        prod = new MockTarget();
        legacy = new MockTarget();
    }

    @AfterEach
    void stop() {
        prod.close();
        legacy.close();
    }

    /** Builds the server the way startup and reload do: a new factory from the manifest and the config. */
    McpServerFactory server(Manifest manifest, String instances) throws IOException {
        Path file = dir.resolve("orders-mcp.yaml");
        Files.writeString(file, "instances:\n" + instances.replace("{prod}", prod.address()).replace("{legacy}", legacy.address()));
        RuntimeConfig config = new ConfigLoader(new Schemas()).load(manifest, new ConfigLoader.Options(Optional.of(file), dir,
                Map.of(), Map.of("storage.root", dir.resolve("data").toString())));
        return new McpServerFactory(manifest, config);
    }

    McpServerFactory server(String instances) throws IOException {
        return server(ORDERS, instances);
    }

    static McpSchema.CallToolResult call(McpServerFactory server, String tool, Map<String, Object> arguments) {
        return server.call(null, new McpSchema.CallToolRequest(tool, arguments, null));
    }

    static McpSchema.CallToolResult getCustomer(McpServerFactory server, String instance) {
        return call(server, "orders_customer_get", Map.of("instance", instance, "path", Map.of("id", "c-1")));
    }

    static JsonNode structured(McpSchema.CallToolResult result) {
        return JSON.valueToTree(result.structuredContent());
    }

    static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    /** Paths the target received, other than the detection requests. */
    static List<String> calls(MockTarget target) {
        return target.received.stream().map(MockTarget.Received::path).toList();
    }

    static List<MockTarget.Received> detections(MockTarget target) {
        return target.versionRequests;
    }

    static JsonNode instance(McpServerFactory server, String name) {
        JsonNode list = structured(call(server, "instances_list", Map.of()));
        for (JsonNode entry : list.path("instances")) {
            if (entry.path("name").asText().equals(name)) {
                return entry;
            }
        }
        throw new AssertionError("instances_list has no " + name + ": " + list);
    }

    static MockTarget.Response version(String version) {
        return MockTarget.Response.json(200, "{\"version\":\"" + version + "\"}");
    }

    // What the runtime does today: pinned contracts, the default for `auto`, per-version tools.

    @Test
    void anAutoAndAPinnedInstanceRunSideBySideOnTheirOwnContracts() throws IOException {
        McpServerFactory server = server("""
                  - { id: prod, baseUrl: "{prod}" }
                  - { id: legacy, baseUrl: "{legacy}", contract: "1.0" }
                """);

        McpSchema.CallToolResult onProd = getCustomer(server, "prod");
        McpSchema.CallToolResult onLegacy = getCustomer(server, "legacy");

        assertThat(onProd.isError()).as(text(onProd)).isFalse();
        assertThat(onLegacy.isError()).as(text(onLegacy)).isFalse();
        assertThat(calls(prod)).containsExactly("/api/v2/customers/c-1");
        assertThat(calls(legacy)).containsExactly("/api/v1/customers/c-1");
        assertThat(onProd.meta()).containsEntry("mcpgp/instance", "prod").containsEntry("mcpgp/contract", "2.0");
        assertThat(onLegacy.meta()).containsEntry("mcpgp/instance", "legacy").containsEntry("mcpgp/contract", "1.0");
    }

    @Test
    void aPinnedInstanceSendsNoDetectionRequest() throws IOException {
        McpServerFactory server = server("""
                  - { id: legacy, baseUrl: "{legacy}", contract: "1.0" }
                """);

        getCustomer(server, "legacy");
        getCustomer(server, "legacy");

        assertThat(detections(legacy)).isEmpty();
        assertThat(calls(legacy)).containsExactly("/api/v1/customers/c-1", "/api/v1/customers/c-1");
    }

    @Test
    void aToolOnlyInTheNewerContractIsNotAvailableOnAnOlderInstance() throws IOException {
        McpServerFactory server = server("""
                  - { id: prod, baseUrl: "{prod}" }
                  - { id: legacy, baseUrl: "{legacy}", contract: "1.0" }
                """);

        McpSchema.CallToolResult onLegacy = call(server, "orders_overdue_invoice_list", Map.of("instance", "legacy"));
        McpSchema.CallToolResult onProd = call(server, "orders_overdue_invoice_list", Map.of("instance", "prod"));

        assertThat(onLegacy.isError()).isTrue();
        assertThat(structured(onLegacy).path("code").asText()).isEqualTo("NOT_AVAILABLE_ON_INSTANCE");
        assertThat(text(onLegacy)).contains("contract 1.0");
        assertThat(calls(legacy)).as("nothing reaches the 1.0 target").isEmpty();
        assertThat(onProd.isError()).as(text(onProd)).isFalse();
        assertThat(calls(prod)).containsExactly("/api/v2/invoices/overdue");
    }

    @Test
    void pinningAVersionThatIsNotShippedStopsStartup() {
        assertThatThrownBy(() -> server("""
                  - { id: legacy, baseUrl: "{legacy}", contract: "3.0" }
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("'3.0' is not shipped")
                .hasMessageContaining("1.0, 2.0, or auto");
        assertThat(legacy.received).isEmpty();
    }

    @Test
    void instancesListShowsEachInstancesContractAndWhatItCanDo() throws IOException {
        McpServerFactory server = server("""
                  - { id: prod, baseUrl: "{prod}" }
                  - { id: legacy, baseUrl: "{legacy}", contract: "1.0" }
                """);

        JsonNode onProd = instance(server, "prod");
        JsonNode onLegacy = instance(server, "legacy");

        assertThat(onProd.path("contract").asText()).isEqualTo("2.0");
        assertThat(onProd.path("contractSelection").asText()).isEqualTo("auto");
        assertThat(onLegacy.path("contract").asText()).isEqualTo("1.0");
        assertThat(onLegacy.path("contractSelection").asText()).isEqualTo("pinned");
        assertThat(onLegacy.path("tools").asLong()).as("1.0 lacks the 2.0-only tools")
                .isLessThan(onProd.path("tools").asLong());
    }

    @Test
    void thePackageVersionFollowsTheDefaultContract() throws IOException {
        JsonNode manifest = JSON.readTree(ORDERS_FILE.toFile());
        assertThat(ORDERS.apis().get("orders").defaultContract()).isEqualTo("2.0");
        assertThat(manifest.at("/server/version").asText()).startsWith("2.0.0");
    }

    /**
     * Detection for {@code contract: auto} (runtime-config §4.4). The compiler writes {@code apis[].detect}
     * into the manifest, but the runtime doesn't read it yet: every {@code auto} instance uses the default
     * contract. Remove {@code @Disabled} when detection is implemented; each test fails today.
     *
     * <p>The spec leaves three things open, which these tests avoid assuming: whether the detection path is
     * taken from the host root or below a contract's base path (they accept any path ending in
     * {@code /version}); what the pattern is matched against when the endpoint returns JSON, as the orders
     * contracts do ({@code {"version": "…"}}); and which error code a refused instance returns.
     */
    @Nested
    class Detection {

        @Test
        void anAutoInstanceUsesTheVersionItsTargetReports() throws IOException {
            prod.respondToVersion(version("1.0.7"));
            McpServerFactory server = server("""
                      - { id: prod, baseUrl: "{prod}" }
                    """);

            McpSchema.CallToolResult result = getCustomer(server, "prod");

            assertThat(detections(prod)).hasSize(1);
            assertThat(detections(prod).get(0).method()).isEqualTo("GET");
            assertThat(calls(prod)).containsExactly("/api/v1/customers/c-1");
            assertThat(result.meta()).containsEntry("mcpgp/contract", "1.0");
            assertThat(instance(server, "prod").path("contract").asText()).isEqualTo("1.0");
        }

        @Test
        void detectionRunsOnceAtStartupNotOnEveryCall() throws IOException {
            prod.respondToVersion(version("2.0.0"));
            McpServerFactory server = server("""
                      - { id: prod, baseUrl: "{prod}" }
                    """);

            getCustomer(server, "prod");
            getCustomer(server, "prod");
            getCustomer(server, "prod");

            assertThat(detections(prod)).hasSize(1);
            assertThat(calls(prod)).hasSize(3).allMatch(p -> p.startsWith("/api/v2/"));
        }

        @Test
        void eachAutoInstanceDetectsItsOwnVersion() throws IOException {
            prod.respondToVersion(version("2.0.3"));
            legacy.respondToVersion(version("1.0.12"));
            McpServerFactory server = server("""
                      - { id: prod, baseUrl: "{prod}" }
                      - { id: legacy, baseUrl: "{legacy}" }
                    """);

            getCustomer(server, "prod");
            getCustomer(server, "legacy");

            assertThat(calls(prod)).containsExactly("/api/v2/customers/c-1");
            assertThat(calls(legacy)).containsExactly("/api/v1/customers/c-1");
            assertThat(call(server, "orders_overdue_invoice_list", Map.of("instance", "legacy")).isError())
                    .as("a detected 1.0 instance has only 1.0's tools").isTrue();
        }

        @Test
        void aFailedDetectionFallsBackToTheDefaultWithUseDefault() throws IOException {
            legacy.respondToVersion(MockTarget.Response.json(503, "{}"));
            McpServerFactory server = server("""
                      - { id: legacy, baseUrl: "{legacy}" }
                    """);

            McpSchema.CallToolResult result = getCustomer(server, "legacy");

            assertThat(detections(legacy)).hasSize(1);
            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(calls(legacy)).containsExactly("/api/v2/customers/c-1");
            assertThat(instance(server, "legacy").path("contract").asText()).isEqualTo("2.0");
        }

        @Test
        void aReportedVersionThatIsNotShippedFallsBackToTheDefault() throws IOException {
            legacy.respondToVersion(version("3.1.0"));
            McpServerFactory server = server("""
                      - { id: legacy, baseUrl: "{legacy}" }
                    """);

            getCustomer(server, "legacy");

            assertThat(detections(legacy)).hasSize(1);
            assertThat(calls(legacy)).containsExactly("/api/v2/customers/c-1");
        }

        @Test
        void refuseMakesOnlyThatInstanceUnavailable() throws IOException {
            ObjectNode root = (ObjectNode) JSON.readTree(ORDERS_FILE.toFile());
            ((ObjectNode) root.at("/apis/0/detect")).put("onFailure", "refuse");
            legacy.respondToVersion(MockTarget.Response.json(503, "{}"));
            McpServerFactory server = server(Manifest.of(root), """
                      - { id: prod, baseUrl: "{prod}", contract: "2.0" }
                      - { id: legacy, baseUrl: "{legacy}" }
                    """);

            McpSchema.CallToolResult onLegacy = getCustomer(server, "legacy");
            McpSchema.CallToolResult onProd = getCustomer(server, "prod");

            assertThat(onLegacy.isError()).isTrue();
            assertThat(calls(legacy)).as("a refused instance sends nothing but detection").isEmpty();
            assertThat(onProd.isError()).as("other instances keep working: " + text(onProd)).isFalse();
            assertThat(calls(prod)).containsExactly("/api/v2/customers/c-1");
        }

        @Test
        void reloadDetectsAgain() throws IOException {
            legacy.respondToVersion(version("1.0.12"));
            String instances = """
                      - { id: legacy, baseUrl: "{legacy}" }
                    """;
            getCustomer(server(instances), "legacy");

            legacy.respondToVersion(version("2.0.0"));   // the customer upgraded their system
            getCustomer(server(instances), "legacy");

            assertThat(detections(legacy)).hasSize(2);
            assertThat(calls(legacy)).containsExactly("/api/v1/customers/c-1", "/api/v2/customers/c-1");
        }

        @Test
        void eachContractsBasePathIsAskedInTurnTheDefaultFirst() throws IOException {
            legacy.respondToVersion(MockTarget.Response.json(404, "{}")).respondToVersion(version("1.0.12"));
            McpServerFactory server = server("""
                      - { id: legacy, baseUrl: "{legacy}" }
                    """);

            getCustomer(server, "legacy");

            assertThat(detections(legacy)).extracting(MockTarget.Received::path).containsExactly("/api/v2/version", "/api/v1/version");
            assertThat(calls(legacy)).containsExactly("/api/v1/customers/c-1");
            assertThat(instance(server, "legacy").path("detectedVersion").asText()).isEqualTo("1.0.12");
        }

        @Test
        void anInstancesPreferredContractIsAskedFirst() throws IOException {
            legacy.respondToVersion(version("1.0.12"));
            McpServerFactory server = server("""
                      - { id: legacy, baseUrl: "{legacy}", prefer: "1.0" }
                    """);

            getCustomer(server, "legacy");

            assertThat(detections(legacy)).extracting(MockTarget.Received::path).containsExactly("/api/v1/version");
            assertThat(calls(legacy)).containsExactly("/api/v1/customers/c-1");
        }

        @Test
        void preferIsOnlyForAutoAndMustBeShipped() {
            assertThatThrownBy(() -> server("""
                      - { id: legacy, baseUrl: "{legacy}", contract: "1.0", prefer: "1.0" }
                    """)).isInstanceOf(ConfigException.class).hasMessageContaining("only for contract: auto");
            assertThatThrownBy(() -> server("""
                      - { id: legacy, baseUrl: "{legacy}", prefer: "3.0" }
                    """)).isInstanceOf(ConfigException.class).hasMessageContaining("'3.0' is not shipped");
        }

        @Test
        void theVersionCanComeFromAHeader() throws IOException {
            ObjectNode root = (ObjectNode) JSON.readTree(ORDERS_FILE.toFile());
            ((ObjectNode) root.at("/apis/0/detect")).remove("field");
            ((ObjectNode) root.at("/apis/0/detect")).put("header", "X-Api-Version");
            legacy.respondToVersion(new MockTarget.Response(200, Map.of("X-Api-Version", "1.0.4"), new byte[0], 0));
            McpServerFactory server = server(Manifest.of(root), """
                      - { id: legacy, baseUrl: "{legacy}" }
                    """);

            getCustomer(server, "legacy");

            assertThat(calls(legacy)).containsExactly("/api/v1/customers/c-1");
        }

        @Test
        void aRefusedInstanceSaysWhyAndIsAskedAgainAfterAMinute() throws IOException {
            ObjectNode root = (ObjectNode) JSON.readTree(ORDERS_FILE.toFile());
            ((ObjectNode) root.at("/apis/0/detect")).put("onFailure", "refuse");
            Manifest manifest = Manifest.of(root);
            MutableClock clock = new MutableClock();
            RuntimeConfig.Instance instance = instance(legacy, "auto", JSON.createObjectNode().put("type", "none"));
            legacy.respondToVersion(MockTarget.Response.json(503, "{}"));
            Contracts contracts = contracts(manifest, instance, clock);

            assertThat(contracts.forCall(instance, Map.of()).available()).isFalse();
            assertThat(contracts.forCall(instance, Map.of()).reason()).contains("HTTP 503");
            assertThat(detections(legacy)).as("not asked again within the minute").hasSize(1);

            clock.now = clock.now.plusSeconds(61);
            legacy.respondToVersion(version("2.0.1"));
            assertThat(contracts.forCall(instance, Map.of()).contract()).isEqualTo("2.0");
            assertThat(detections(legacy)).hasSize(2);

            // and through a call: the agent sees why
            legacy.respondToVersion(MockTarget.Response.json(503, "{}"));
            McpSchema.CallToolResult refused = getCustomer(server(manifest, """
                      - { id: legacy, baseUrl: "{legacy}" }
                    """), "legacy");
            assertThat(structured(refused).path("code").asText()).isEqualTo("INSTANCE_UNAVAILABLE");
            assertThat(text(refused)).contains("HTTP 503");
        }

        @Test
        void whenTheTargetWantsCredentialsTheFirstCallersAreUsed() throws IOException {
            RuntimeConfig.Instance instance = instance(legacy, "auto", JSON.createObjectNode().put("type", "fromRequest")
                    .put("header", "X-Orders-Authorization").put("as", "Authorization"));
            legacy.respondToVersion(MockTarget.Response.json(401, "{}")).respondToVersion(MockTarget.Response.json(401, "{}"))
                    .respondToVersion(MockTarget.Response.json(404, "{}")).respondToVersion(version("1.0.3"));
            Contracts contracts = contracts(ORDERS, instance, new MutableClock());

            Contracts.Choice before = contracts.of(instance);
            Contracts.Choice first = contracts.forCall(instance, Map.of("x-orders-authorization", "Bearer user-1"));

            assertThat(before.contract()).as("without credentials: the default, for now").isEqualTo("2.0");
            assertThat(first.contract()).isEqualTo("1.0");
            assertThat(detections(legacy)).extracting(r -> r.header("Authorization"))
                    .containsExactly(null, null, "Bearer user-1", "Bearer user-1");
            contracts.forCall(instance, Map.of("x-orders-authorization", "Bearer user-2"));
            assertThat(detections(legacy)).as("known now: nobody's credential is used again").hasSize(4);
        }
    }

    static RuntimeConfig.Instance instance(MockTarget target, String contract, JsonNode auth) {
        return new RuntimeConfig.Instance("orders", "legacy", target.address(), contract, null, false, 10, auth,
                JSON.createObjectNode(), null, Path.of("."));
    }

    static Contracts contracts(Manifest manifest, RuntimeConfig.Instance instance, java.time.Clock clock) {
        Contracts contracts = new Contracts(manifest, List.of(instance), new Upstream(Contracts.apiKeys(manifest),
                new Redactor(java.util.Set.of())), clock);
        contracts.start();
        return contracts;
    }

    static final class MutableClock extends java.time.Clock {
        java.time.Instant now = java.time.Instant.parse("2026-10-13T09:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }
}
