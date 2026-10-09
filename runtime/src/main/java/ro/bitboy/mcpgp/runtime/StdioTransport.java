package ro.bitboy.mcpgp.runtime;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The stdio transport, offering only the MCP revisions the manifest supports, and calling
 * {@code onClose} once when the client closes stdin (the server then shuts down).
 */
final class StdioTransport extends StdioServerTransportProvider {

    private final List<String> revisions;

    StdioTransport(McpJsonMapper mapper, InputStream in, OutputStream out, List<String> revisions, Runnable onClose) {
        super(mapper, new ClosingInput(in, onClose), out);
        this.revisions = List.copyOf(revisions);
    }

    @Override
    public List<String> protocolVersions() {
        return revisions;
    }

    private static final class ClosingInput extends FilterInputStream {
        private final Runnable onClose;
        private final AtomicBoolean closed = new AtomicBoolean();

        ClosingInput(InputStream in, Runnable onClose) {
            super(in);
            this.onClose = onClose;
        }

        @Override
        public int read() throws IOException {
            return eof(super.read());
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return eof(super.read(b, off, len));
        }

        private int eof(int n) {
            if (n < 0 && closed.compareAndSet(false, true)) {
                Thread shutdown = new Thread(onClose, "stdin-closed");
                shutdown.setDaemon(true);
                shutdown.start();
            }
            return n;
        }
    }
}
