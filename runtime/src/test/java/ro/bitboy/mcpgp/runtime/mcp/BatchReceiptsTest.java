package ro.bitboy.mcpgp.runtime.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchReceiptsTest {
    @TempDir Path dir;

    @Test void unfinishedReceiptMakesTheOutcomeUnknownAfterRestart() {
        Map<String, Object> arguments = Map.of("requestId", "write-1", "calls", List.of(Map.of("id", "a")));
        new BatchReceipts(dir, Clock.systemUTC()).claim(null, "write-1", arguments);

        assertThatThrownBy(() -> new BatchReceipts(dir, Clock.systemUTC()).existing(null, "write-1", arguments))
                .isInstanceOf(ToolError.class)
                .hasMessageContaining("did not record a final result");
    }

    @Test void completedReceiptReplaysOnlyForTheSameArguments() {
        Map<String, Object> arguments = Map.of("requestId", "read-1", "calls", List.of(Map.of("id", "a")));
        BatchReceipts receipts = new BatchReceipts(dir, Clock.systemUTC());
        var claim = receipts.claim(null, "read-1", arguments);
        assertThat(receipts.complete(claim, Map.of("batchId", "b_1", "results", List.of()))).isTrue();

        var replay = new BatchReceipts(dir, Clock.systemUTC()).existing(null, "read-1", arguments);
        assertThat(replay.completed()).containsEntry("batchId", "b_1");
        assertThatThrownBy(() -> receipts.existing(null, "read-1", Map.of("requestId", "read-1", "calls", List.of())))
                .isInstanceOf(ToolError.class)
                .hasMessageContaining("different batch arguments");
    }
}
