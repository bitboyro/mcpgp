package ro.bitboy.mcpgp.runtime.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

/**
 * The audit file (audit-and-errors §1): JSON Lines, one event per line, never stdout. Rotated
 * {@code daily}, {@code hourly} or by {@code size:<MB>}; rotated files older than {@code retainDays}
 * are deleted. The directory and files are owner-only where the file system supports it.
 */
public final class AuditLog {

    private static final Logger LOG = LoggerFactory.getLogger(AuditLog.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss").withZone(ZoneOffset.UTC);

    private final RuntimeConfig.Audit config;
    private final String server;
    private final String build;
    private final Clock clock;
    private String period;

    public AuditLog(RuntimeConfig.Audit config, String server, String build, Clock clock) {
        this.config = config;
        this.server = server;
        this.build = build;
        this.clock = clock;
        if (config.enabled()) {
            try {
                Path dir = config.file().toAbsolutePath().getParent();
                Files.createDirectories(dir);
                ownerOnly(dir, "rwx------");
                period = Files.exists(config.file()) ? period(Files.getLastModifiedTime(config.file()).toInstant()) : period(clock.instant());
                cleanup();
            } catch (IOException e) {
                throw new IllegalStateException("The audit directory " + config.file().getParent() + " cannot be created: " + e.getMessage());
            }
        }
    }

    public String argumentsMode() {
        return config.arguments();
    }

    /** A new event with the fields every event has; the caller adds the rest and calls {@link #write}. */
    public ObjectNode event(String type, String outcome, String cid) {
        ObjectNode event = JSON.createObjectNode();
        event.put("ts", DateTimeFormatter.ISO_INSTANT.format(clock.instant().truncatedTo(ChronoUnit.MILLIS)));
        event.put("v", 1);
        event.put("type", type);
        event.put("server", server);
        event.put("build", build);
        event.put("outcome", outcome);
        event.put("cid", cid);
        return event;
    }

    public synchronized void write(ObjectNode event) {
        if (!config.enabled()) {
            return;
        }
        try {
            rotateIfDue();
            boolean created = !Files.exists(config.file());
            Files.writeString(config.file(), JSON.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (created) {
                ownerOnly(config.file(), "rw-------");
            }
        } catch (JsonProcessingException e) {
            LOG.error("An audit event could not be encoded");
        } catch (IOException e) {
            LOG.error("Writing the audit file {} failed: {}", config.file(), e.getMessage());
        }
    }

    private void rotateIfDue() throws IOException {
        Path file = config.file();
        if (!Files.exists(file)) {
            period = period(clock.instant());
            return;
        }
        String rotate = config.rotate();
        String suffix = null;
        if (rotate.startsWith("size:")) {
            long limit = Long.parseLong(rotate.substring("size:".length())) * 1024 * 1024;
            if (Files.size(file) >= limit) {
                suffix = STAMP.format(clock.instant());
            }
        } else {
            String now = period(clock.instant());
            if (!now.equals(period)) {
                suffix = period;
                period = now;
            }
        }
        if (suffix != null) {
            String name = file.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String rotated = dot > 0 ? name.substring(0, dot) + "-" + suffix + name.substring(dot) : name + "-" + suffix;
            Files.move(file, file.resolveSibling(rotated));
            cleanup();
        }
    }

    private String period(Instant instant) {
        return config.rotate().equals("hourly") ? HOUR.format(instant) : DAY.format(instant);
    }

    /** Deletes rotated files older than {@code retainDays}; the current file is never deleted. */
    private void cleanup() throws IOException {
        Path file = config.file().toAbsolutePath();
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) + "-" : name + "-";
        Instant cutoff = clock.instant().minus(config.retainDays(), ChronoUnit.DAYS);
        try (Stream<Path> files = Files.list(file.getParent())) {
            for (Path rotated : files.filter(p -> p.getFileName().toString().startsWith(base)).toList()) {
                if (Files.getLastModifiedTime(rotated).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(rotated);
                }
            }
        }
    }

    private static void ownerOnly(Path path, String permissions) {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
            } catch (IOException | UnsupportedOperationException e) {
                LOG.warn("Cannot restrict permissions of {}", path);
            }
        }
    }

    /** For tests: the files in the audit directory. */
    List<Path> files() throws IOException {
        try (Stream<Path> files = Files.list(config.file().toAbsolutePath().getParent())) {
            return files.sorted().toList();
        }
    }
}
