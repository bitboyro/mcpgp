package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import ro.bitboy.mcpgp.runtime.audit.Arguments;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.stream.Stream;
import java.util.Map;

/**
 * Durable receipts for caller-supplied batch request ids. A receipt is written before dispatch, so a
 * crash after a write is conservatively reported as an unknown outcome instead of replaying it.
 */
final class BatchReceipts {

    record Claim(String requestId, String owner, String argumentsHash, Path file, Map<String, Object> completed) {
        boolean replay() {
            return completed != null;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final Path root;
    private final Clock clock;
    private final long completedTtlSeconds;
    private final int maxStoredReceipts;

    BatchReceipts(Path storageRoot, Clock clock, long completedTtlSeconds, int maxStoredReceipts) {
        this.root = storageRoot.resolve("batches").toAbsolutePath().normalize();
        this.clock = clock;
        this.completedTtlSeconds = completedTtlSeconds;
        this.maxStoredReceipts = maxStoredReceipts;
    }

    synchronized Claim existing(McpSyncServerExchange exchange, String requestId, Map<String, Object> arguments) {
        if (requestId == null) return null;
        validate(requestId);
        String owner = owner(exchange);
        String hash = Arguments.sha256(Arguments.canonical(JSON.valueToTree(arguments)));
        Path file = root.resolve(Arguments.sha256(owner + "\u0000" + requestId) + ".json");
        try {
            purgeExpired();
            if (!Files.exists(file)) return null;
            return read(file, owner, requestId, hash);
        } catch (IOException e) {
            throw new ToolError("BATCH_RECEIPT_UNAVAILABLE", "Could not read requestId " + requestId,
                    "Do not replay the batch until the server storage is available");
        }
    }

    synchronized Claim claim(McpSyncServerExchange exchange, String requestId, Map<String, Object> arguments) {
        validate(requestId);
        String owner = owner(exchange);
        String hash = Arguments.sha256(Arguments.canonical(JSON.valueToTree(arguments)));
        Path file = root.resolve(Arguments.sha256(owner + "\u0000" + requestId) + ".json");
        try {
            Files.createDirectories(root);
            purgeExpired();
            if (Files.exists(file)) {
                return read(file, owner, requestId, hash);
            }
            if (receiptCount() >= maxStoredReceipts) {
                throw new ToolError("BATCH_RECEIPT_STORAGE_FULL", "The request receipt storage limit has been reached",
                        "No operation was sent; wait for completed receipts to expire or increase maxStoredReceipts");
            }
            ObjectNode created = JSON.createObjectNode();
            created.put("requestId", requestId);
            created.put("owner", owner);
            created.put("argumentsHash", hash);
            created.put("state", "running");
            created.put("started", clock.instant().toString());
            write(file, created);
            return new Claim(requestId, owner, hash, file, null);
        } catch (IOException e) {
            throw new ToolError("BATCH_RECEIPT_UNAVAILABLE", "Could not record requestId " + requestId,
                    "No operation was sent; try again after the server storage is available");
        }
    }

    private static void validate(String requestId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new ToolError("INPUT_VALIDATION", "requestId must contain 1–128 letters, digits, '.', '_', ':' or '-'");
        }
    }

    private Claim read(Path file, String owner, String requestId, String hash) throws IOException {
        JsonNode node = JSON.readTree(Files.readString(file));
        if (!owner.equals(node.path("owner").asText()) || !requestId.equals(node.path("requestId").asText())) {
            throw new ToolError("BATCH_REQUEST_ID_REUSE", "requestId is already owned by another caller");
        }
        if (!hash.equals(node.path("argumentsHash").asText())) {
            throw new ToolError("BATCH_REQUEST_ID_REUSE", "requestId was already used for different batch arguments");
        }
        if (node.path("state").asText().equals("complete")) {
            return new Claim(requestId, owner, hash, file, JSON.convertValue(node.path("result"), MAP));
        }
        throw new ToolError("BATCH_OUTCOME_UNKNOWN", "A previous call with this requestId did not record a final result",
                "Do not replay the batch; inspect the target system before deciding how to recover")
                .withDetails(Map.of("requestId", requestId));
    }

    /** Removes only completed receipts. An unfinished receipt must remain to prevent unsafe replay. */
    private void purgeExpired() throws IOException {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                JsonNode node = JSON.readTree(Files.readString(file));
                if (!"complete".equals(node.path("state").asText())) continue;
                try {
                    Instant completed = Instant.parse(node.path("completed").asText());
                    if (!completed.plusSeconds(completedTtlSeconds).isAfter(clock.instant())) Files.deleteIfExists(file);
                } catch (java.time.format.DateTimeParseException ignored) {
                    // A malformed completion timestamp is retained conservatively for operator inspection.
                }
            }
        }
    }

    private long receiptCount() throws IOException {
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json")).count();
        }
    }

    synchronized boolean complete(Claim claim, Map<String, Object> result) {
        if (claim == null) {
            return true;
        }
        try {
            ObjectNode completed = JSON.createObjectNode();
            completed.put("requestId", claim.requestId());
            completed.put("owner", claim.owner());
            completed.put("argumentsHash", claim.argumentsHash());
            completed.put("state", "complete");
            completed.put("completed", clock.instant().toString());
            completed.set("result", JSON.valueToTree(result));
            write(claim.file(), completed);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String owner(McpSyncServerExchange exchange) {
        if (exchange == null) {
            return "stdio";
        }
        Object client = exchange.transportContext() == null ? null : exchange.transportContext().get(McpServerFactory.CLIENT_KEY);
        return client == null ? "session:" + String.valueOf(exchange.sessionId()) : "client:" + client;
    }

    private static void write(Path file, ObjectNode value) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.writeValueAsString(value), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
