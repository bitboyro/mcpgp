package ro.bitboy.mcpgp.runtime.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchReceiptsTest {
    @TempDir Path dir;

    @Test void unfinishedReceiptMakesTheOutcomeUnknownAfterRestart() {
        Map<String, Object> arguments = Map.of("requestId", "write-1", "calls", List.of(Map.of("id", "a")));
        new BatchReceipts(dir, Clock.systemUTC(), 60, 10).claim(null, "write-1", arguments);

        assertThatThrownBy(() -> new BatchReceipts(dir, Clock.systemUTC(), 60, 10).existing(null, "write-1", arguments))
                .isInstanceOf(ToolError.class)
                .hasMessageContaining("did not record a final result");
    }

    @Test void completedReceiptReplaysOnlyForTheSameArguments() {
        Map<String, Object> arguments = Map.of("requestId", "read-1", "calls", List.of(Map.of("id", "a")));
        BatchReceipts receipts = new BatchReceipts(dir, Clock.systemUTC(), 60, 10);
        var claim = receipts.claim(null, "read-1", arguments);
        assertThat(receipts.complete(claim, Map.of("batchId", "b_1", "results", List.of()))).isTrue();

        var replay = new BatchReceipts(dir, Clock.systemUTC(), 60, 10).existing(null, "read-1", arguments);
        assertThat(replay.completed()).containsEntry("batchId", "b_1");
        assertThatThrownBy(() -> receipts.existing(null, "read-1", Map.of("requestId", "read-1", "calls", List.of())))
                .isInstanceOf(ToolError.class)
                .hasMessageContaining("different batch arguments");
    }

    @Test void completedReceiptsExpireButUnfinishedReceiptsDoNot() {
        Instant started = Instant.parse("2026-01-01T00:00:00Z");
        Clock then = Clock.fixed(started, ZoneOffset.UTC);
        Map<String, Object> completed = Map.of("requestId", "complete-1", "calls", List.of(Map.of("id", "a")));
        Map<String, Object> running = Map.of("requestId", "running-1", "calls", List.of(Map.of("id", "b")));
        BatchReceipts receipts = new BatchReceipts(dir, then, 60, 10);
        var claim = receipts.claim(null, "complete-1", completed);
        receipts.complete(claim, Map.of("batchId", "b_1"));
        receipts.claim(null, "running-1", running);

        Clock later = Clock.fixed(started.plus(Duration.ofSeconds(61)), ZoneOffset.UTC);
        BatchReceipts afterExpiry = new BatchReceipts(dir, later, 60, 10);
        assertThat(afterExpiry.existing(null, "complete-1", completed)).isNull();
        assertThatThrownBy(() -> afterExpiry.existing(null, "running-1", running))
                .isInstanceOf(ToolError.class)
                .hasMessageContaining("did not record a final result");
    }

    @Test void receiptLimitRejectsNewWorkBeforeItCanBeRecorded() {
        BatchReceipts receipts = new BatchReceipts(dir, Clock.systemUTC(), 60, 1);
        Map<String, Object> first = Map.of("requestId", "first", "calls", List.of(Map.of("id", "a")));
        Map<String, Object> second = Map.of("requestId", "second", "calls", List.of(Map.of("id", "b")));
        receipts.claim(null, "first", first);

        assertThatThrownBy(() -> receipts.claim(null, "second", second))
                .isInstanceOf(ToolError.class)
                .hasMessageContaining("storage limit");
    }
}
