package ro.bitboy.mcpgp.runtime.mcp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class RetainedResultsTest {
    @Test void aFullStoreDoesNotEvictLiveResultsOrReturnAFakeId() {
        var policies = new ObjectMapper().createObjectNode();
        policies.put("responseLimitKb", 1); policies.putObject("results").put("mode", "reference").put("maxStoredKb", 1);
        var store = new RetainedResults(policies, Clock.systemUTC());
        var first = store.format(null, new ToolOutput(Map.of("data", "x".repeat(600)), null, Map.of()));
        String id = ((Map<?, ?>)first.structured()).get("resultId").toString();
        var second = store.format(null, new ToolOutput(Map.of("data", "x".repeat(600)), null, Map.of()));
        assertThat(((Map<?, ?>)second.structured()).containsKey("retained")).isTrue();
        assertThat(((Map<?, ?>)second.structured()).containsKey("resultId")).isFalse();
        assertThat(store.get(null, Map.of("resultId", id, "pointer", "/data"))).isNotNull();
    }
    @Test void resultsCannotBeReadByAnotherSession() {
        var policies = new ObjectMapper().createObjectNode();
        policies.putObject("results").put("mode", "reference");
        var store = new RetainedResults(policies, Clock.systemUTC());
        var first = org.mockito.Mockito.mock(io.modelcontextprotocol.server.McpSyncServerExchange.class);
        var second = org.mockito.Mockito.mock(io.modelcontextprotocol.server.McpSyncServerExchange.class);
        org.mockito.Mockito.when(first.sessionId()).thenReturn("first");
        org.mockito.Mockito.when(second.sessionId()).thenReturn("second");
        var out = store.format(first, new ToolOutput(Map.of("secret", "private"), null, Map.of()));
        String id = ((Map<?, ?>)out.structured()).get("resultId").toString();
        assertThatThrownBy(() -> store.get(second, Map.of("resultId", id))).isInstanceOf(ToolError.class);
        assertThat(store.get(first, Map.of("resultId", id))).isNotNull();
    }
    @Test void snapshotsAreImmutableAndExpire() {
        var policies = new ObjectMapper().createObjectNode();
        policies.put("responseLimitKb", 1); policies.putObject("results").put("mode", "reference").put("ttlSeconds", 1);
        class MutableClock extends Clock {
            Instant now = Instant.EPOCH;
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now; }
        }
        var clock = new MutableClock(); var store = new RetainedResults(policies, clock);
        Map<String, Object> original = new HashMap<>(Map.of("answer", 42));
        var output = store.format(null, new ToolOutput(original, null, Map.of()));
        String id = ((Map<?, ?>)output.structured()).get("resultId").toString(); original.put("answer", 7);
        var saved = (Map<?, ?>)store.get(null, Map.of("resultId", id));
        assertThat(saved.get("value").toString()).contains("42");
        clock.now = clock.now.plusSeconds(2);
        assertThatThrownBy(() -> store.get(null, Map.of("resultId", id))).isInstanceOf(ToolError.class).hasMessageContaining("expired");
    }
}
