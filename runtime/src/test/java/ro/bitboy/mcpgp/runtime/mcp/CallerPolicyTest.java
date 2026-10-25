package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.call.MockTarget;
import ro.bitboy.mcpgp.runtime.config.ConfigException;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Identity policy files, organization policy files and caller session narrowing (policy-sources-and-sessions.md),
 * as a caller sees them: what is listed, what is refused, and that a refused call never reaches the target.
 */
class CallerPolicyTest {
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();
    static final String GET = "orders_customer_get";       // reads
    static final String CREATE = "orders_customer_create"; // writes
    static final Map<String, Object> ON_PRODUCTION = Map.of("instance", "production", "path", Map.of("id", "1"));
    static final Map<String, Object> ON_STAGING = Map.of("instance", "staging", "path", Map.of("id", "1"));

    @TempDir Path dir;
    MockTarget target;
    Manifest manifest;

    @BeforeEach void start() throws Exception {
        target = new MockTarget();
        // a manifest of its own, so session state (kept per manifest) never leaks between tests
        manifest = Manifest.file(REPO.resolve("examples/manifest/orders-mcp.manifest.json"));
    }

    @AfterEach void stop() {
        CallerAccess.clear(manifest);
        target.close();
    }

    RuntimeConfig config(String deployment, String organization, String identity, Map<String, String> flags) throws Exception {
        Path file = Files.writeString(dir.resolve("server.yaml"), deployment + """
                instances:
                  - { id: production, baseUrl: "%1$s", contract: "2.0" }
                  - { id: staging, baseUrl: "%1$s", contract: "2.0" }
                """.formatted(target.address()));
        Optional<Path> org = organization == null ? Optional.empty() : Optional.of(Files.writeString(dir.resolve("organization.yaml"), organization));
        Optional<Path> ids = identity == null ? Optional.empty() : Optional.of(Files.writeString(dir.resolve("identity.yaml"), identity));
        Map<String, String> all = new java.util.HashMap<>(flags);
        all.put("storage.root", dir.resolve("data").toString());
        return new ConfigLoader(new Schemas()).load(manifest, new ConfigLoader.Options(Optional.of(file), dir, Map.of(), all, org, ids));
    }

    McpServerFactory server(String deployment, String organization, String identity) throws Exception {
        return new McpServerFactory(manifest, config(deployment, organization, identity, Map.of()));
    }

    static McpSchema.CallToolResult call(McpServerFactory server, String name, Map<String, Object> args) {
        return server.call(null, new McpSchema.CallToolRequest(name, args));
    }

    /** What a caller sees and gets: listing and calls under that caller's identity, scopes and session. */
    record Caller(McpServerFactory server, String principal, Set<String> scopes, String session) {
        boolean lists(String tool) {
            try (var ignored = server.catalog().bind(principal, scopes, session)) { return server.catalog().tool(tool).isPresent(); }
        }
        McpSchema.CallToolResult call(String tool, Map<String, Object> args) {
            try (var ignored = server.catalog().bind(principal, scopes, session)) { return CallerPolicyTest.call(server, tool, args); }
        }
        JsonNode sessionPolicy(Map<String, Object> args) {
            McpSchema.CallToolResult result = call("session_policy", args);
            assertThat(result.isError()).as("%s", result.content()).isFalse();
            return JSON.valueToTree(result.structuredContent());
        }
        String error(String tool, Map<String, Object> args) {
            McpSchema.CallToolResult result = call(tool, args);
            assertThat(result.isError()).as("%s should be refused", tool).isTrue();
            return result.content().toString();
        }
    }

    Caller as(McpServerFactory server, String principal, String... scopes) {
        return new Caller(server, principal, Set.of(scopes), "s-" + principal);
    }

    @Nested class IdentityPolicy {
        @Test void aSubjectsRestrictionAppliesOnlyToThatSubject() throws Exception {
            McpServerFactory server = server("", null, """
                    version: 1
                    subjects:
                      "static:alice": { tools: { allow: [orders_customer_get] } }
                    """);
            Caller alice = as(server, "static:alice");
            Caller bob = as(server, "static:bob");
            assertThat(alice.lists(GET)).isTrue();
            assertThat(alice.lists(CREATE)).isFalse();
            assertThat(bob.lists(CREATE)).isTrue();

            // hidden from alice: answered as if it didn't exist, so its existence isn't revealed
            assertThat(alice.error(CREATE, Map.of("instance", "production", "body", Map.of("name", "x")))).contains("Unknown tool");
            assertThat(alice.error("call_tool", Map.of("name", CREATE, "arguments", Map.of("instance", "production", "body", Map.of("name", "x"))))).isNotEmpty();
            assertThat(target.received).isEmpty();
            assertThat(alice.call(GET, ON_PRODUCTION).isError()).isFalse();
            assertThat(target.received).hasSize(1);
        }

        @Test void aSubjectCanBeLimitedToSomeTargetServers() throws Exception {
            McpServerFactory server = server("", null, """
                    version: 1
                    subjects:
                      "oauth:carol": { instances: ["orders/staging"] }
                    """);
            Caller carol = as(server, "oauth:carol");
            assertThat(carol.error(GET, ON_PRODUCTION)).contains("NOT_AVAILABLE_ON_INSTANCE");
            assertThat(target.received).isEmpty();
            assertThat(carol.call(GET, ON_STAGING).isError()).isFalse();
            assertThat(target.received).hasSize(1);
        }

        @Test void theDefaultRestrictsEveryCallerAndReadOnlyHidesWrites() throws Exception {
            McpServerFactory server = server("", null, "version: 1\ndefault: { tools: { readOnly: true } }\n");
            for (Caller caller : List.of(as(server, "static:alice"), as(server, "anonymous"), as(server, "stdio"))) {
                assertThat(caller.lists(GET)).isTrue();
                assertThat(caller.lists(CREATE)).isFalse();
            }
        }

        @Test void scopeGrantsUniteAndAnyGrantedScopesDenialWins() throws Exception {
            McpServerFactory server = server("", null, """
                    version: 1
                    scopes:
                      orders.read: { tools: { allow: ["orders_customer_*"] } }
                      orders.safe: { tools: { deny: [orders_customer_create] } }
                      orders.search: { tools: { allow: [orders_order_search] } }
                    """);
            Caller reader = as(server, "oauth:dave", "orders.read");
            assertThat(reader.lists(GET)).isTrue();
            assertThat(reader.lists(CREATE)).isTrue();
            assertThat(reader.lists("orders_order_search")).isFalse();

            Caller both = as(server, "oauth:erin", "orders.read", "orders.search");
            assertThat(both.lists(GET)).isTrue();
            assertThat(both.lists("orders_order_search")).isTrue();

            Caller safe = as(server, "oauth:frank", "orders.read", "orders.safe");
            assertThat(safe.lists(GET)).isTrue();
            assertThat(safe.lists(CREATE)).isFalse();
        }

        @Test void anOAuthCallerHoldingNoneOfTheListedScopesGetsNoTools() throws Exception {
            McpServerFactory server = server("", null, "version: 1\nscopes:\n  orders.read: { tools: { allow: ['orders_*'] } }\n");
            assertThat(as(server, "oauth:gina").lists(GET)).isFalse();
            assertThat(as(server, "oauth:gina", "other.scope").lists(GET)).isFalse();
            assertThat(as(server, "oauth:gina", "other.scope").error(GET, ON_PRODUCTION)).contains("Unknown tool");
            assertThat(target.received).isEmpty();
            assertThat(as(server, "oauth:hana", "orders.read").lists(GET)).isTrue();
        }

        @Test void scopeRulesDontApplyToCallersWithoutScopesWhoFollowTheDefaultAndTheirSubject() throws Exception {
            // a shared file: scopes written for OAuth servers, a subject for this API-key server's bot
            McpServerFactory server = server("", null, """
                    version: 1
                    default: { tools: { deny: [orders_order_search] } }
                    subjects:
                      "static:ci-bot": { tools: { allow: [orders_customer_get] } }
                    scopes:
                      orders.read: { tools: { allow: [orders_customer_get] } }
                    """);
            Caller bot = as(server, "static:ci-bot");
            assertThat(bot.lists(GET)).isTrue();
            assertThat(bot.lists(CREATE)).isFalse();
            for (Caller other : List.of(as(server, "static:other"), as(server, "anonymous"), as(server, "stdio"))) {
                assertThat(other.lists(CREATE)).isTrue();
                assertThat(other.lists("orders_order_search")).isFalse();
            }
            assertThat(bot.call(GET, ON_PRODUCTION).isError()).isFalse();
        }

        @Test void aScopesSectionOnAServerWithoutOAuthIsReportedAtStartup() throws Exception {
            String identity = "version: 1\nscopes:\n  orders.read: { tools: { allow: ['orders_*'] } }\n";
            assertThat(config("", null, identity, Map.of()).warnings())
                    .anyMatch(w -> w.contains("scopes section doesn't apply") && w.contains("over stdio"));
            assertThat(config("", null, "version: 1\ndefault: { tools: { readOnly: true } }\n", Map.of()).warnings())
                    .noneMatch(w -> w.contains("scopes section"));
        }

        @Test void identityRestrictionsNarrowDeploymentPolicyAndNeverWidenIt() throws Exception {
            McpServerFactory server = server("tools: { deny: [orders_customer_get] }\n", null, """
                    version: 1
                    subjects:
                      "static:alice": { tools: { allow: [orders_customer_get, orders_customer_create] } }
                    """);
            Caller alice = as(server, "static:alice");
            assertThat(alice.lists(GET)).isFalse();
            assertThat(alice.lists(CREATE)).isTrue();
        }

        @Test void aSelectedFileThatIsMissingOrInvalidStopsStartup() throws Exception {
            Path config = Files.writeString(dir.resolve("plain.yaml"), "instances: [{id: production, baseUrl: localhost, contract: '2.0'}]\n");
            assertThatThrownBy(() -> new ConfigLoader(new Schemas()).load(manifest, new ConfigLoader.Options(Optional.of(config), dir, Map.of(),
                    Map.of(), Optional.empty(), Optional.of(dir.resolve("missing.yaml")))))
                    .isInstanceOf(ConfigException.class).hasMessageContaining("IDENTITY_POLICY");
            assertThatThrownBy(() -> config("", null, "version: 1\nsubjects: { \"static:a\": { tools: { allow: [orders_customer_get] }, roles: [x] } }\n", Map.of()))
                    .isInstanceOf(ConfigException.class).hasMessageContaining("IDENTITY_POLICY");
            assertThatThrownBy(() -> config("", null, "version: 1\ndefault: { tools: { allow: [orders_typo] } }\n", Map.of()))
                    .isInstanceOf(ConfigException.class).hasMessageContaining("orders_typo");
        }
    }

    @Nested class OrganizationPolicy {
        @Test void itsToolDenialWinsOverADeploymentThatAllowsTheTool() throws Exception {
            McpServerFactory server = server("tools: { allow: [orders_customer_get, orders_customer_create] }\n",
                    "version: 1\ntools: { deny: [orders_customer_create] }\n", null);
            Caller anyone = as(server, "stdio");
            assertThat(anyone.lists(GET)).isTrue();
            assertThat(anyone.lists(CREATE)).isFalse();
            anyone.error(CREATE, Map.of("instance", "production", "body", Map.of("name", "x")));
            assertThat(target.received).isEmpty();
        }

        @Test void itsDefaultsComeBeforeTheDeploymentsSettings() throws Exception {
            String organization = "version: 1\ndefaults: { logging: { level: warn }, audit: { file: { retainDays: 60 } } }\n";
            RuntimeConfig inherited = config("", organization, null, Map.of());
            assertThat(inherited.logLevel()).isEqualTo("warn");
            assertThat(inherited.audit().retainDays()).isEqualTo(60);
            RuntimeConfig overridden = config("logging: { level: debug }\n", organization, null, Map.of());
            assertThat(overridden.logLevel()).isEqualTo("debug");
        }

        @Test void constraintsRejectADeploymentThatLoosensThem() throws Exception {
            assertThatThrownBy(() -> config("", "version: 1\nconstraints: { requireReadOnly: true }\n", null, Map.of()))
                    .isInstanceOf(ConfigException.class).hasMessageContaining("requires readOnly on orders/production");
            assertThatThrownBy(() -> config("audit: { file: { retainDays: 7 } }\n", "version: 1\nconstraints: { minAuditRetainDays: 30 }\n", null, Map.of()))
                    .isInstanceOf(ConfigException.class).hasMessageContaining("minimum audit retention");
            assertThatThrownBy(() -> config("audit: { file: { enabled: false } }\n", "version: 1\nconstraints: { requireAudit: true }\n", null, Map.of()))
                    .isInstanceOf(ConfigException.class).hasMessageContaining("requires audit");
            assertThatThrownBy(() -> config("", "version: 1\nconstraints: { allowedTransports: [http] }\n", null, Map.of()))
                    .isInstanceOf(ConfigException.class).hasMessageContaining("allowedTransports");
        }

        @Test void aCommandLineFlagCannotBypassAConstraint() throws Exception {
            String organization = "version: 1\nconstraints: { maxPerMinute: 60 }\n";
            assertThat(config("", organization, null, Map.of("instances.0.rateLimits.perMinute", "30", "instances.1.rateLimits.perMinute", "30")).logLevel()).isNotNull();
            assertThatThrownBy(() -> config("", organization, null, Map.of("instances.0.rateLimits.perMinute", "500", "instances.1.rateLimits.perMinute", "30")))
                    .isInstanceOf(ConfigException.class).hasMessageContaining("maxPerMinute exceeded on production");
        }

        @Test void aSelectedFileThatIsInvalidStopsStartup() {
            assertThatThrownBy(() -> config("", "version: 1\nconstraints: { maxPerHour: 5 }\n", null, Map.of()))
                    .isInstanceOf(ConfigException.class).hasMessageContaining("ORGANIZATION_POLICY");
        }
    }

    @Nested class SessionNarrowing {
        static final String ENABLED = "sessions: { enabled: true }\n";

        @Test void theSessionToolExistsOnlyWhenSessionsAreOn() throws Exception {
            assertThat(as(server("", null, null), "stdio").lists("session_policy")).isFalse();
            assertThat(as(server(ENABLED, null, null), "stdio").lists("session_policy")).isTrue();
        }

        @Test void aRestrictionNarrowsListingAndCallsForThatSessionOnly() throws Exception {
            McpServerFactory server = server(ENABLED, null, null);
            Caller alice = new Caller(server, "static:alice", Set.of(), "one");
            JsonNode after = alice.sessionPolicy(Map.of("action", "restrict", "tools", Map.of("allow", List.of(GET))));
            assertThat(after.path("version").asInt()).isEqualTo(1);
            assertThat(alice.lists(GET)).isTrue();
            assertThat(alice.lists(CREATE)).isFalse();
            alice.error(CREATE, Map.of("instance", "production", "body", Map.of("name", "x")));
            assertThat(target.received).isEmpty();

            // another session of the same caller, and another caller with the same session id, are untouched
            assertThat(new Caller(server, "static:alice", Set.of(), "two").lists(CREATE)).isTrue();
            assertThat(new Caller(server, "static:bob", Set.of(), "one").lists(CREATE)).isTrue();
            assertThat(alice.sessionPolicy(Map.of()).path("restrictions").size()).isEqualTo(1);
        }

        @Test void laterRestrictionsIntersectSoNothingCanBeWidened() throws Exception {
            Caller alice = new Caller(server(ENABLED, null, null), "static:alice", Set.of(), "one");
            alice.sessionPolicy(Map.of("action", "restrict", "tools", Map.of("allow", List.of(GET))));
            alice.sessionPolicy(Map.of("action", "restrict", "tools", Map.of("allow", List.of(GET, CREATE))));
            assertThat(alice.lists(GET)).isTrue();
            assertThat(alice.lists(CREATE)).isFalse();
            assertThat(alice.error("session_policy", Map.of("action", "clear"))).contains("INPUT_VALIDATION");
            assertThat(alice.lists(CREATE)).isFalse();
        }

        @Test void aSessionCanGiveUpTargetServers() throws Exception {
            Caller alice = new Caller(server(ENABLED, null, null), "static:alice", Set.of(), "one");
            alice.sessionPolicy(Map.of("action", "restrict", "instances", List.of("orders/staging")));
            assertThat(alice.error(GET, ON_PRODUCTION)).contains("NOT_AVAILABLE_ON_INSTANCE");
            assertThat(target.received).isEmpty();
            assertThat(alice.call(GET, ON_STAGING).isError()).isFalse();
        }

        @Test void aSessionCannotReachBeyondItsIdentity() throws Exception {
            McpServerFactory server = server(ENABLED, null, """
                    version: 1
                    subjects:
                      "static:alice": { tools: { allow: [orders_customer_get] } }
                    """);
            Caller alice = new Caller(server, "static:alice", Set.of(), "one");
            alice.sessionPolicy(Map.of("action", "restrict", "tools", Map.of("allow", List.of(CREATE))));
            assertThat(alice.lists(CREATE)).isFalse();
            assertThat(alice.lists(GET)).isFalse();
        }

        @Test void invalidRestrictionsAreRefusedAndChangeNothing() throws Exception {
            Caller alice = new Caller(server(ENABLED, null, null), "static:alice", Set.of(), "one");
            assertThat(alice.error("session_policy", Map.of("action", "restrict", "tools", Map.of("allow", List.of("orders_typo"))))).contains("INPUT_VALIDATION");
            assertThat(alice.error("session_policy", Map.of("action", "restrict", "instances", List.of("orders/nowhere")))).contains("INPUT_VALIDATION");
            assertThat(alice.error("session_policy", Map.of("action", "restrict", "tools", Map.of("allow", List.of("${env:HOME}"))))).contains("INPUT_VALIDATION");
            assertThat(alice.error("session_policy", Map.of("action", "restrict", "roles", List.of("admin")))).contains("INPUT_VALIDATION");
            assertThat(alice.sessionPolicy(Map.of()).path("restrictions").size()).isZero();
            assertThat(alice.lists(CREATE)).isTrue();
        }

        @Test void endingTheSessionForgetsItsRestrictions() throws Exception {
            McpServerFactory server = server(ENABLED, null, null);
            Caller alice = new Caller(server, "static:alice", Set.of(), "one");
            alice.sessionPolicy(Map.of("action", "restrict", "tools", Map.of("allow", List.of(GET))));
            server.catalog().forget("static:alice", "one");
            assertThat(alice.lists(CREATE)).isTrue();
        }
    }
}
