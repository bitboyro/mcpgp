package ro.bitboy.mcpgp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The stdio transport, offering only the MCP revisions the manifest supports. When the client closes
 * stdin, the server first answers every request it has already read (up to {@code drainTimeout}); the
 * SDK sees the end of input only then, and {@code onClose} runs once to shut down.
 */
final class StdioTransport extends StdioServerTransportProvider {

    private static final Logger LOG = LoggerFactory.getLogger(StdioTransport.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> revisions;

    StdioTransport(McpJsonMapper mapper, InputStream in, OutputStream out, List<String> revisions,
                   Duration drainTimeout, Runnable onClose) {
        this(mapper, new Pending(), in, out, revisions, drainTimeout, onClose);
    }

    private StdioTransport(McpJsonMapper mapper, Pending pending, InputStream in, OutputStream out, List<String> revisions,
                           Duration drainTimeout, Runnable onClose) {
        super(mapper, new TappedInput(in, pending::inbound, () -> pending.drain(drainTimeout), onClose),
                new TappedOutput(out, pending::outbound));
        this.revisions = List.copyOf(revisions);
    }

    @Override
    public List<String> protocolVersions() {
        return revisions;
    }

    /** Ids of client requests read but not yet answered. */
    static final class Pending {
        private final Set<String> ids = new HashSet<>();

        void inbound(String line) {
            JsonNode message = parse(line);
            if (message != null && message.has("method") && message.hasNonNull("id")) {
                synchronized (this) {
                    ids.add(message.get("id").toString());
                }
            }
        }

        void outbound(String line) {
            JsonNode message = parse(line);
            if (message != null && !message.has("method") && message.hasNonNull("id")) {
                synchronized (this) {
                    ids.remove(message.get("id").toString());
                    notifyAll();
                }
            }
        }

        /** Waits until every request read so far is answered, or the timeout passes. */
        void drain(Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            synchronized (this) {
                while (!ids.isEmpty()) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        LOG.warn("stdin closed with {} request(s) still running; stopping anyway", ids.size());
                        break;
                    }
                    try {
                        wait(Math.max(1, left / 1_000_000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        private static JsonNode parse(String line) {
            try {
                return line.isBlank() ? null : JSON.readTree(line);
            } catch (IOException e) {
                return null;
            }
        }
    }

    /** Collects bytes into lines for a callback. */
    private static final class Lines {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final Consumer<String> sink;

        Lines(Consumer<String> sink) {
            this.sink = sink;
        }

        synchronized void add(byte[] bytes, int off, int len) {
            for (int i = off; i < off + len; i++) {
                if (bytes[i] == '\n') {
                    sink.accept(buffer.toString(StandardCharsets.UTF_8));
                    buffer.reset();
                } else {
                    buffer.write(bytes[i]);
                }
            }
        }
    }

    /**
     * Reads stdin for the SDK. At end of input it first waits for {@code drain} (so the SDK keeps its
     * session open and answers what it has read), then reports EOF and starts {@code onClose}.
     */
    private static final class TappedInput extends FilterInputStream {
        private final Lines lines;
        private final Runnable drain;
        private final Runnable onClose;
        private final AtomicBoolean closed = new AtomicBoolean();

        TappedInput(InputStream in, Consumer<String> sink, Runnable drain, Runnable onClose) {
            super(in);
            this.lines = new Lines(sink);
            this.drain = drain;
            this.onClose = onClose;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                lines.add(new byte[] {(byte) b}, 0, 1);
            }
            return eof(b);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                lines.add(b, off, n);
            }
            return eof(n);
        }

        private int eof(int n) {
            if (n < 0 && closed.compareAndSet(false, true)) {
                drain.run();
                // not a daemon: it keeps the process alive until shutdown is done
                new Thread(onClose, "stdin-closed").start();
            }
            return n;
        }
    }

    private static final class TappedOutput extends FilterOutputStream {
        private final Lines lines;

        TappedOutput(OutputStream out, Consumer<String> sink) {
            super(out);
            this.lines = new Lines(sink);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            lines.add(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            lines.add(b, off, len);
        }
    }
}
