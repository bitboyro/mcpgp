package ro.bitboy.mcpgp.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.manifest.ManifestWriter;
import ro.bitboy.mcpgp.packager.Packager;
import ro.bitboy.mcpgp.packager.trial.MockRun;
import ro.bitboy.mcpgp.platform.api.ApiException;
import ro.bitboy.mcpgp.platform.store.Model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Trying tools against a mock of the API (spec/platform-api.md §2.1): the server mcpgp packages runs
 * with the version's manifest against a mock made from its contracts, on the loopback address, so a
 * call shows what the agent gets and what would be sent, and reaches no real system. Extensions are
 * left out: the platform never runs its builders' code. A few servers stay warm per version; idle ones
 * stop after five minutes.
 */
public final class Trials implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Trials.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RUNNING = 3;
    private static final long IDLE_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private record Session(MockRun run, Path dir, List<String> leftOut, long[] lastUsed) {
    }

    private final Compilation compilation;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcpgp-trials");
        t.setDaemon(true);
        return t;
    });

    public Trials(Compilation compilation) {
        this.compilation = compilation;
        reaper.scheduleAtFixedRate(this::closeIdle, 1, 1, TimeUnit.MINUTES);
    }

    private final java.util.concurrent.ExecutorService warming = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mcpgp-trial-warm");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Starts the version's trial server in the background (opening the Try panel), so the first call
     * doesn't wait for it; a call that comes first waits for the same start. Failures are left for
     * the call to report.
     */
    public void warm(Model.Version version, String createdAt) {
        synchronized (sessions) {
            if (sessions.containsKey(version.digest())) {
                sessions.get(version.digest()).lastUsed()[0] = System.currentTimeMillis();
                return;
            }
        }
        warming.submit(() -> {
            try {
                session(version, createdAt);
            } catch (RuntimeException e) {
                LOG.debug("Warming the trial server of {} failed: {}", version.digest().substring(0, 12), e.getMessage());
            }
        });
    }

    /** One call of a tool of the version: {@code result} or {@code error}, the {@code requests} the mock got, and what was {@code leftOut}. */
    public ObjectNode call(Model.Version version, String createdAt, String tool, Map<String, Object> arguments) {
        Session session = session(version, createdAt);
        synchronized (session) {
            session.lastUsed()[0] = System.currentTimeMillis();
            try {
                ObjectNode answer = (ObjectNode) session.run().call(tool, arguments);
                answer.set("leftOut", JSON.valueToTree(session.leftOut()));
                return answer;
            } catch (IOException e) {
                stop(version.digest());
                throw new ApiException(502, "TRIAL_FAILED", "the trial server stopped: " + e.getMessage());
            }
        }
    }

    private Session session(Model.Version version, String createdAt) {
        synchronized (sessions) {
            Session existing = sessions.get(version.digest());
            if (existing != null) {
                return existing;
            }
            while (sessions.size() >= MAX_RUNNING) {
                String oldest = sessions.entrySet().stream().min(Comparator.comparingLong(e -> e.getValue().lastUsed()[0]))
                        .map(Map.Entry::getKey).orElseThrow();
                stopLocked(oldest);
            }
            Compiler.Result result = compilation.compile(version, new ManifestWriter.Build(Instant.parse(createdAt), Compiler.VERSION, null, null, null));
            if (result.manifest().isEmpty()) {
                throw new ApiException(422, "VALIDATION", "this version doesn't compile; the checks say why");
            }
            List<String> leftOut = new ArrayList<>();
            ObjectNode manifest = MockRun.withoutExtensions(result.manifest().get(), leftOut);
            Path dir;
            MockRun run;
            try {
                InputStream runtime = Packager.runtimeJar();
                if (runtime == null) {
                    throw new ApiException(503, "UNAVAILABLE", "this platform was built without the server runtime");
                }
                dir = Files.createTempDirectory("mcpgp-trial-");
                run = new MockRun(manifest, result.contracts().values(), runtime, dir);
            } catch (IOException e) {
                throw new ApiException(503, "UNAVAILABLE", "the trial server can't start: " + e.getMessage());
            }
            String smoke = run.smoke();
            if (smoke != null) {
                run.close();
                delete(dir);
                throw new ApiException(503, "UNAVAILABLE", "the trial server didn't start: " + smoke);
            }
            Session session = new Session(run, dir, List.copyOf(leftOut), new long[] {System.currentTimeMillis()});
            sessions.put(version.digest(), session);
            LOG.info("Trial server for version {} started", version.digest().substring(0, 12));
            return session;
        }
    }

    private void closeIdle() {
        long now = System.currentTimeMillis();
        synchronized (sessions) {
            new ArrayList<>(sessions.entrySet()).stream().filter(e -> now - e.getValue().lastUsed()[0] > IDLE_MILLIS)
                    .forEach(e -> stopLocked(e.getKey()));
        }
    }

    private void stop(String digest) {
        synchronized (sessions) {
            stopLocked(digest);
        }
    }

    private void stopLocked(String digest) {
        Session session = sessions.remove(digest);
        if (session != null) {
            session.run().close();
            delete(session.dir());
        }
    }

    private static void delete(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // a temporary directory
        }
    }

    @Override
    public void close() {
        warming.shutdownNow();
        reaper.shutdownNow();
        synchronized (sessions) {
            new ArrayList<>(sessions.keySet()).forEach(this::stopLocked);
        }
    }
}
