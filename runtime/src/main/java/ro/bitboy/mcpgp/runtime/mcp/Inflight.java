package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Requests that can be cancelled ({@code notifications/cancelled}, MCP cancellation utility). The SDK
 * doesn't expose a request's id to a handler, so each transport tags {@code tools/call} and
 * {@code resources/read} on the way in: it registers the request here and adds a {@code _meta} entry
 * with the key of its {@link Handle}. The handler finds its handle through that entry; a cancel
 * notification, read by the same transport, finds the same handle by (scope, request id). The scope is
 * the HTTP session, or one stdio connection.
 */
public final class Inflight {

    private static final Logger LOG = LoggerFactory.getLogger(Inflight.class);

    /** The {@code _meta} key the transports set (and always overwrite: a client can't choose it). */
    public static final String META = "mcpgp/inflight";

    private static final Set<String> TRACKED = Set.of("tools/call", "resources/read");
    private static final Map<String, Handle> ACTIVE = new ConcurrentHashMap<>();
    private static final ThreadLocal<Handle> CURRENT = new ThreadLocal<>();

    private Inflight() {
    }

    /** One request that may be cancelled. */
    public static final class Handle {
        private final String key;
        private final List<Runnable> hooks = new CopyOnWriteArrayList<>();
        private volatile boolean cancelled;
        private volatile String reason;

        private Handle(String key) {
            this.key = key;
        }

        public boolean cancelled() {
            return cancelled;
        }

        public String reason() {
            return reason;
        }

        /** Runs {@code hook} when the request is cancelled (at once when it already was). */
        public void onCancel(Runnable hook) {
            hooks.add(hook);
            if (cancelled) {
                hook.run();
            }
        }

        public void forget(Runnable hook) {
            hooks.remove(hook);
        }
    }

    /**
     * Prepares one message read from a client: tags a trackable request, and answers a cancel notification.
     * Mutates {@code message}.
     *
     * @param scope the HTTP session id, or the id of the stdio connection
     * @return the key to {@link #end} when the request is answered, or null when nothing was registered
     */
    public static String accept(String scope, JsonNode message) {
        if (message == null || !message.isObject() || !(message.path("method").isTextual())) {
            return null;
        }
        String method = message.path("method").asText();
        if (method.equals("notifications/cancelled")) {
            cancel(scope, message.path("params").get("requestId"), message.path("params").path("reason").asText(null));
            return null;
        }
        JsonNode params = message.get("params");
        if (params instanceof ObjectNode object && object.path("_meta").has(META)) {
            ((ObjectNode) object.get("_meta")).remove(META);      // never trust a client's own
        }
        if (!TRACKED.contains(method) || !message.hasNonNull("id") || !(params instanceof ObjectNode object)) {
            return null;
        }
        String key = key(scope, message.get("id"));
        ACTIVE.put(key, new Handle(key));
        JsonNode meta = object.get("_meta");
        ObjectNode target = meta instanceof ObjectNode existing ? existing : object.putObject("_meta");
        target.put(META, key);
        return key;
    }

    /** True when {@code message} is a cancel notification (HTTP answers those itself). */
    public static boolean isCancel(JsonNode message) {
        return message != null && message.isObject() && message.path("method").asText().equals("notifications/cancelled");
    }

    /** The request is answered (or its connection is gone): it can't be cancelled any more. */
    public static void end(String key) {
        if (key != null) {
            ACTIVE.remove(key);
        }
    }

    /** True when the client cancelled the request with this key and it hasn't been answered. */
    public static boolean cancelled(String key) {
        Handle handle = key == null ? null : ACTIVE.get(key);
        return handle != null && handle.cancelled;
    }

    /** The handle of the request a handler was given, from its {@code _meta}; null when it wasn't tagged. */
    public static Handle of(Map<String, Object> meta) {
        Object key = meta == null ? null : meta.get(META);
        return key == null ? null : ACTIVE.get(key.toString());
    }

    /**
     * Cancels a request by the id the client names. Unknown or finished requests are ignored (the cancel
     * and the answer crossed; the spec expects that).
     */
    static boolean cancel(String scope, JsonNode requestId, String reason) {
        if (requestId == null || requestId.isNull()) {
            return false;
        }
        Handle handle = ACTIVE.get(key(scope, requestId));
        if (handle == null) {
            LOG.debug("Cancel for request {} ignored: unknown or already answered", requestId);
            return false;
        }
        handle.reason = reason;
        handle.cancelled = true;
        LOG.info("Request {} cancelled by the client{}", requestId, reason == null ? "" : " (" + reason + ")");
        for (Runnable hook : handle.hooks) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                LOG.debug("Cancel hook failed: {}", e.getMessage());
            }
        }
        return true;
    }

    public static String key(String scope, JsonNode id) {
        return scope + "|" + id.toString();
    }

    /** The handle the current thread's call runs for, so deep code (the upstream call) can stop with it. */
    public static Handle current() {
        return CURRENT.get();
    }

    /** Makes {@code handle} the current one; give the result back to {@link #restore} (a discovery call's inner call nests). */
    public static Handle bind(Handle handle) {
        Handle outer = CURRENT.get();
        if (handle != null) {
            CURRENT.set(handle);
        }
        return outer;
    }

    public static void restore(Handle outer) {
        if (outer == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(outer);
        }
    }
}
