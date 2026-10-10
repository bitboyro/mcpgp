package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import ro.bitboy.mcpgp.runtime.audit.Arguments;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Confirmation of tools marked {@code confirm} (tools-contract §8). Clients that support elicitation
 * are asked directly; others get {@code CONFIRMATION_REQUIRED} with a token, which is single-use,
 * expires after 5 minutes, and is bound to the tool, the instance, the exact arguments and the session.
 */
final class Confirmations {

    static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a token was issued for. */
    private record Binding(String tool, String instance, String argumentsHash, String session, Instant expires) {
    }

    private final Map<String, Binding> tokens = new ConcurrentHashMap<>();
    private final Clock clock;

    Confirmations(Clock clock) {
        this.clock = clock;
    }

    /**
     * Returns when the call may go ahead; throws {@code CONFIRMATION_REQUIRED} otherwise.
     *
     * @param arguments the arguments without {@code confirm}
     * @param audit     receives {@code asked}, {@code given}, {@code refused} or {@code expired}
     */
    void check(McpSyncServerExchange exchange, JsonNode tool, Map<String, Object> arguments, String token,
               Consumer<String> audit) {
        String name = tool.path("name").asText();
        String instance = arguments.get("instance") == null ? "" : arguments.get("instance").toString();
        String hash = Arguments.sha256(Arguments.canonical(JSON.valueToTree(arguments)));
        String session = exchange == null || exchange.sessionId() == null ? "" : exchange.sessionId();
        String summary = summary(tool, instance);

        if (token != null) {
            Binding binding = tokens.remove(token);       // single use, whatever happens next
            if (binding == null) {
                audit.accept("expired");
                throw required(issue(name, instance, hash, session), summary, "The confirmation token is unknown or was already used");
            }
            if (clock.instant().isAfter(binding.expires())) {
                audit.accept("expired");
                throw required(issue(name, instance, hash, session), summary, "The confirmation token expired");
            }
            if (!binding.tool().equals(name) || !binding.instance().equals(instance) || !binding.argumentsHash().equals(hash)
                    || !binding.session().equals(session)) {
                audit.accept("refused");
                throw required(issue(name, instance, hash, session), summary, "The confirmation token was issued for a different call");
            }
            audit.accept("given");
            return;
        }

        if (supportsElicitation(exchange)) {
            audit.accept("asked");
            McpSchema.ElicitResult answer = exchange.createElicitation(new McpSchema.ElicitFormRequest(summary + " Confirm to go ahead.",
                    Map.of("type", "object",
                            "properties", Map.of("confirm", Map.of("type", "boolean", "title", "Go ahead",
                                    "description", "Run " + name + (instance.isEmpty() ? "" : " on " + instance))),
                            "required", List.of("confirm")), null));
            boolean yes = answer != null && answer.action() == McpSchema.ElicitResult.Action.ACCEPT
                    && answer.content() != null && Boolean.TRUE.equals(answer.content().get("confirm"));
            audit.accept(yes ? "given" : "refused");
            if (!yes) {
                throw new ToolError("CONFIRMATION_REQUIRED", "The user did not confirm " + name + "; nothing was done");
            }
            return;
        }

        audit.accept("asked");
        throw required(issue(name, instance, hash, session), summary, null);
    }

    private String issue(String tool, String instance, String hash, String session) {
        purge();
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        tokens.put(token, new Binding(tool, instance, hash, session, clock.instant().plus(LIFETIME)));
        return token;
    }

    private void purge() {
        Instant now = clock.instant();
        tokens.values().removeIf(b -> now.isAfter(b.expires()));
    }

    private static ToolError required(String token, String summary, String reason) {
        ToolError error = new ToolError("CONFIRMATION_REQUIRED", (reason == null ? "" : reason + ". ") + summary,
                "Ask the user. If they agree, call the tool again with the same arguments plus \"confirm\": \"" + token
                        + "\" (valid for 5 minutes, once)");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("confirm", token);
        details.put("expiresInSeconds", LIFETIME.toSeconds());
        return error.withDetails(details);
    }

    private static String summary(JsonNode tool, String instance) {
        String title = tool.path("title").asText(tool.path("name").asText());
        boolean destructive = tool.at("/annotations/destructiveHint").asBoolean(false);
        return title + (instance.isEmpty() ? "" : " on " + instance) + (destructive ? ": this changes data and may not be undoable." : ".");
    }

    private static boolean supportsElicitation(McpSyncServerExchange exchange) {
        return exchange != null && exchange.getClientCapabilities() != null && exchange.getClientCapabilities().elicitation() != null;
    }
}
