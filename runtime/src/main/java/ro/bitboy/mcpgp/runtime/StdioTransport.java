package ro.bitboy.mcpgp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import ro.bitboy.mcpgp.runtime.mcp.Inflight;
import ro.bitboy.mcpgp.runtime.mcp.ListPager;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The stdio transport, offering only the MCP revisions the manifest supports. When the client closes
 * stdin, the server first answers every request it has already read (up to {@code drainTimeout}); the
 * SDK sees the end of input only then, and {@code onClose} runs once to shut down.
 */
final class StdioTransport extends StdioServerTransportProvider {

    private static final Logger LOG = LoggerFactory.getLogger(StdioTransport.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> revisions;

    private final String scope;
    private final int pageSize;
    private final Pending pending;
    private final Map<String, Boolean> toolLists;
    private volatile Supplier<List<io.modelcontextprotocol.spec.McpSchema.Tool>> toolList = List::of;
    private volatile String session;

    /** @param pageSize list operations answer this many items at a time ({@code server.pageSize}); 0 = all at once */
    StdioTransport(McpJsonMapper mapper, InputStream in, OutputStream out, List<String> revisions,
                   Duration drainTimeout, Runnable onClose, int pageSize) {
        this(mapper, "stdio:" + java.util.UUID.randomUUID(), in, out, revisions, drainTimeout, onClose, pageSize);
    }

    private StdioTransport(McpJsonMapper mapper, String scope, InputStream in, OutputStream out, List<String> revisions,
                           Duration drainTimeout, Runnable onClose, int pageSize) {
        this(mapper, new Pending(scope), scope, in, out, revisions, drainTimeout, onClose, pageSize);
    }

    private StdioTransport(McpJsonMapper mapper, Pending pending, String scope, InputStream in, OutputStream out, List<String> revisions,
                           Duration drainTimeout, Runnable onClose, int pageSize) {
        this(mapper, pending, scope, in, out, revisions, drainTimeout, onClose, pageSize, new ConcurrentHashMap<>());
    }

    private StdioTransport(McpJsonMapper mapper, Pending pending, String scope, InputStream in, OutputStream out, List<String> revisions,
                           Duration drainTimeout, Runnable onClose, int pageSize, Map<String, Boolean> toolLists) {
        super(mapper, new TappedInput(new Tagging(in, scope, pageSize, toolLists), pending::inbound, () -> pending.drain(drainTimeout), onClose),
                new TappedOutput(out, pending::outbound));
        this.revisions = List.copyOf(revisions);
        this.scope = scope;
        this.pageSize = pageSize;
        this.pending = pending;
        this.toolLists = toolLists;
    }

    /** Supplies the session-specific result for {@code tools/list}; all tools stay registered for dispatch. */
    void toolList(Supplier<List<io.modelcontextprotocol.spec.McpSchema.Tool>> supplier) {
        this.toolList = supplier;
    }

    /** The one stdio session, learned when a session-scoped handler changes its tool list. */
    void session(String session) {
        this.session = session;
    }

    String session() {
        return session;
    }

    @Override
    public List<String> protocolVersions() {
        return revisions;
    }

    /** Every session's messages go out one at a time ({@link SerialSends}). */
    @Override
    public void setSessionFactory(io.modelcontextprotocol.spec.McpServerSession.Factory factory) {
        super.setSessionFactory(transport -> factory.create(new SerialSends(transport, scope, pageSize, pending, toolLists, () -> toolList.get())));
    }

    /**
     * Sends one message at a time. The SDK's stdio transport hands each message to its output with a
     * single attempt, which fails when another thread is handing over one at the same moment; the
     * message is then dropped ("Failed to enqueue message") and the client never gets that answer.
     * Parallel tool calls finish at the same moment often enough for that to happen.
     */
    static final class SerialSends implements io.modelcontextprotocol.spec.McpServerTransport {
        private final io.modelcontextprotocol.spec.McpServerTransport delegate;
        private final Object lock = new Object();
        private final String scope;
        private final int pageSize;
        private final Pending pending;
        private final Map<String, Boolean> toolLists;
        private final Supplier<List<io.modelcontextprotocol.spec.McpSchema.Tool>> toolList;

        SerialSends(io.modelcontextprotocol.spec.McpServerTransport delegate, String scope, int pageSize, Pending pending,
                    Map<String, Boolean> toolLists, Supplier<List<io.modelcontextprotocol.spec.McpSchema.Tool>> toolList) {
            this.delegate = delegate;
            this.scope = scope;
            this.pageSize = pageSize;
            this.pending = pending;
            this.toolLists = toolLists;
            this.toolList = toolList;
        }

        /** The client cancelled this request: the spec says not to answer it. */
        private boolean cancelled(io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage message) {
            if (!(message instanceof io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse response) || response.id() == null) {
                return false;
            }
            JsonNode id = JSON.valueToTree(response.id());
            if (!Inflight.cancelled(Inflight.key(scope, id))) {
                return false;
            }
            pending.answered(id);
            return true;
        }

        /** A list answer is cut to one page ({@link ListPager}) before it goes out. */
        private io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage paged(io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage message) {
            if (!(message instanceof io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse response)
                    || response.id() == null || response.result() == null) {
                return message;
            }
            JsonNode id = JSON.valueToTree(response.id());
            Object result = response.result();
            if (toolLists.remove(Inflight.key(scope, id)) != null) {
                Map<String, Object> filtered = new java.util.LinkedHashMap<>(JSON.convertValue(result, Map.class));
                filtered.put("tools", toolList.get());
                result = filtered;
            }
            if (pageSize <= 0) {
                return result == response.result() ? message
                        : new io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse("2.0", response.id(), result, null);
            }
            ListPager.Pending pending = ListPager.take(Inflight.key(scope, JSON.valueToTree(response.id())));
            if (pending == null) {
                return result == response.result() ? message
                        : new io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse("2.0", response.id(), result, null);
            }
            JsonNode answered = ListPager.answer(pending, JSON.createObjectNode().put("jsonrpc", "2.0")
                    .<com.fasterxml.jackson.databind.node.ObjectNode>set("id", JSON.valueToTree(response.id()))
                    .<com.fasterxml.jackson.databind.node.ObjectNode>set("result", JSON.valueToTree(result)), pageSize);
            if (answered.has("error")) {
                return new io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse("2.0", response.id(), null,
                        new io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError(answered.at("/error/code").asInt(),
                                answered.at("/error/message").asText(), null));
            }
            return new io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse("2.0", response.id(),
                    JSON.convertValue(answered.get("result"), Map.class), null);
        }

        @Override
        public reactor.core.publisher.Mono<Void> sendMessage(io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage original) {
            if (cancelled(original)) {
                return reactor.core.publisher.Mono.empty();
            }
            io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage message = paged(original);
            // once the transport is ready, subscribing hands the message over at once: inside the lock
            return reactor.core.publisher.Mono.create(sink -> {
                synchronized (lock) {
                    delegate.sendMessage(message).subscribe(v -> { }, sink::error, sink::success);
                }
            });
        }

        @Override
        public reactor.core.publisher.Mono<Void> closeGracefully() {
            return delegate.closeGracefully();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public <T> T unmarshalFrom(Object data, io.modelcontextprotocol.json.TypeRef<T> type) {
            return delegate.unmarshalFrom(data, type);
        }

        @Override
        public List<String> protocolVersions() {
            return delegate.protocolVersions();
        }
    }

    /** Ids of client requests read but not yet answered. */
    static final class Pending {
        private final Set<String> ids = new HashSet<>();
        private final String scope;

        Pending(String scope) {
            this.scope = scope;
        }

        void inbound(String line) {
            JsonNode message = parse(line);
            if (message != null && message.has("method") && message.hasNonNull("id")) {
                synchronized (this) {
                    ids.add(message.get("id").toString());
                }
            }
        }

        /** A request that is never answered (the client cancelled it): it no longer holds up shutdown. */
        void answered(JsonNode id) {
            Inflight.end(Inflight.key(scope, id));
            synchronized (this) {
                ids.remove(id.toString());
                notifyAll();
            }
        }

        void outbound(String line) {
            JsonNode message = parse(line);
            if (message != null && !message.has("method") && message.hasNonNull("id")) {
                Inflight.end(Inflight.key(scope, message.get("id")));
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

        static JsonNode parse(String line) {
            try {
                return line.isBlank() ? null : JSON.readTree(line);
            } catch (IOException e) {
                return null;
            }
        }
    }

    /**
     * Reads stdin a line at a time and lets {@link Inflight} tag the requests that can be cancelled, and
     * see the cancel notifications, before the SDK reads them.
     */
    private static final class Tagging extends InputStream {
        private final InputStream in;
        private final String scope;
        private byte[] line = new byte[0];
        private int position;

        private final int pageSize;
        private final Map<String, Boolean> toolLists;

        Tagging(InputStream in, String scope, int pageSize, Map<String, Boolean> toolLists) {
            this.in = new java.io.BufferedInputStream(in);
            this.scope = scope;
            this.pageSize = pageSize;
            this.toolLists = toolLists;
        }

        @Override
        public int read() throws IOException {
            if (position >= line.length && !fill()) {
                return -1;
            }
            return line[position++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (position >= line.length && !fill()) {
                return -1;
            }
            int n = Math.min(len, line.length - position);
            System.arraycopy(line, position, b, off, n);
            position += n;
            return n;
        }

        private boolean fill() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) >= 0) {
                buffer.write(c);
                if (c == '\n') {
                    break;
                }
            }
            if (buffer.size() == 0) {
                return false;
            }
            line = tag(buffer.toByteArray());
            position = 0;
            return true;
        }

        private byte[] tag(byte[] raw) {
            JsonNode message = Pending.parse(new String(raw, StandardCharsets.UTF_8));
            if (message == null) {
                return raw;
            }
            ListPager.accept(scope, message, pageSize);       // noted only; the line goes on unchanged
            if (message.path("method").asText().equals("tools/list") && message.hasNonNull("id")) {
                toolLists.put(Inflight.key(scope, message.get("id")), Boolean.TRUE);
            }
            if (Inflight.accept(scope, message) == null) {
                return raw;
            }
            try {
                return (JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8);
            } catch (IOException e) {
                return raw;
            }
        }

        @Override
        public int available() throws IOException {
            return position < line.length ? line.length - position : in.available();
        }

        @Override
        public void close() throws IOException {
            in.close();
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
