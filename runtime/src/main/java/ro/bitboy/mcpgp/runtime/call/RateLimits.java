package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.mcp.ToolError;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Limits on calls to each target server (definition {@code safety.rateLimits}, on by default; an
 * instance's {@code rateLimits} in the runtime config changes them): at most {@code concurrent} calls
 * at once, more wait up to {@link #WAIT} for a free slot; at most {@code perMinute} calls in any
 * minute. A call over a limit answers {@code RATE_LIMITED} with how long to wait.
 */
final class RateLimits {

    static final Duration WAIT = Duration.ofSeconds(30);

    record Limit(boolean enabled, int concurrent, int perMinute) {
    }

    private final class Gate {
        final Limit limit;
        final Semaphore slots;
        final ArrayDeque<Long> started = new ArrayDeque<>();

        Gate(Limit limit) {
            this.limit = limit;
            this.slots = new Semaphore(limit.concurrent(), true);
        }
    }

    private final Limit build;
    private final Clock clock;
    private final Map<String, Gate> gates = new ConcurrentHashMap<>();

    RateLimits(Manifest manifest, Clock clock) {
        JsonNode rates = manifest.policies().path("rateLimits");
        this.build = new Limit(rates.path("enabled").asBoolean(true), rates.path("concurrent").asInt(8), rates.path("perMinute").asInt(120));
        this.clock = clock;
    }

    /** The instance's limits: the build's, with the operator's changes. */
    Limit of(RuntimeConfig.Instance instance) {
        JsonNode own = instance.rateLimits() == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : instance.rateLimits();
        return new Limit(own.path("enabled").asBoolean(build.enabled()), own.path("concurrent").asInt(build.concurrent()),
                own.path("perMinute").asInt(build.perMinute()));
    }

    /** Takes a slot for one call to the instance's target; closing it frees the slot. */
    AutoCloseable enter(RuntimeConfig.Instance instance) {
        Limit limit = of(instance);
        if (!limit.enabled()) {
            return () -> { };
        }
        Gate gate = gates.computeIfAbsent(instance.api() + "/" + instance.id(), k -> new Gate(limit));
        long now = clock.millis();
        synchronized (gate.started) {
            while (!gate.started.isEmpty() && now - gate.started.peekFirst() >= 60_000) {
                gate.started.pollFirst();
            }
            if (gate.started.size() >= gate.limit.perMinute()) {
                long wait = Math.max(1, (60_000 - (now - gate.started.peekFirst()) + 999) / 1000);
                throw limited(instance, gate.limit.perMinute() + " calls in the last minute, the most this server makes to " + instance.id(), wait);
            }
            gate.started.addLast(now);
        }
        boolean got;
        try {
            got = gate.slots.tryAcquire(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolError("INTERNAL", "the call was interrupted");
        }
        if (!got) {
            throw limited(instance, gate.limit.concurrent() + " calls to " + instance.id() + " were still running after " + WAIT.toSeconds() + " s", 5);
        }
        return gate.slots::release;
    }

    private static ToolError limited(RuntimeConfig.Instance instance, String why, long seconds) {
        return new ToolError("RATE_LIMITED", "Not sent: " + why, "Wait " + seconds + " s before calling " + instance.id()
                + " again; fewer calls at once also help").withMeta(Map.of("mcpgp/retryAfterSeconds", seconds));
    }
}
