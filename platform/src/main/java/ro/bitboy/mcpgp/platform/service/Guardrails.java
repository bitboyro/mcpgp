package ro.bitboy.mcpgp.platform.service;

import ro.bitboy.mcpgp.platform.PlatformConfig;
import ro.bitboy.mcpgp.platform.api.ApiException;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Agent guardrails (spec/platform-api.md §5): quotas per agent per hour, and approvals: an agent's
 * release becomes a request a person approves (the build is released) or rejects.
 */
public final class Guardrails {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Store store;
    private final Builds builds;
    private final PlatformConfig.Guardrails limits;
    private final Clock clock;

    public Guardrails(Store store, Builds builds, PlatformConfig.Guardrails limits, Clock clock) {
        this.store = store;
        this.builds = builds;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * Throws {@code QUOTA_EXCEEDED} (429) when an agent made too many builds ({@code builds}) or new
     * versions ({@code versions}, including new servers) in the last hour.
     */
    public void quota(Access.Principal who, String what) {
        if (!who.identity().kind().equals("agent")) {
            return;
        }
        boolean builds = what.equals("builds");
        int limit = builds ? limits.agentBuildsPerHour() : limits.agentVersionsPerHour();
        java.util.Set<String> actions = builds ? java.util.Set.of("build.start") : java.util.Set.of("version.create", "server.create");
        Instant since = clock.instant().minus(Duration.ofHours(1));
        // the last hour only: reading stops there however long the log is
        long used = store.activity(new ro.bitboy.mcpgp.platform.store.Store.ActivityQuery(null, null, who.identity().name(), null, actions, since),
                limit + 1).size();
        if (used >= limit) {
            throw new ApiException(429, "QUOTA_EXCEEDED", "agent " + who.identity().name() + " reached its limit of " + limit + " "
                    + what + " per hour; try again later", Map.of("limit", limit, "window", "1h"));
        }
    }

    /** An agent asks for a release: a pending approval. */
    public Model.Approval requestRelease(Model.Build build, Access.Principal who) {
        if (!build.status().equals("succeeded") || build.deleted()) {
            throw new ApiException(409, "CONFLICT", "only succeeded builds with files can be released");
        }
        for (Model.Approval a : store.approvals()) {
            if (a.target().equals(build.id()) && a.status().equals("pending")) {
                return a;
            }
        }
        byte[] id = new byte[6];
        RANDOM.nextBytes(id);
        Model.Approval approval = new Model.Approval("ap-" + HexFormat.of().formatHex(id), "build.release", build.id(),
                who.identity().name(), clock.instant().toString(), "pending", null, null, null);
        store.saveApproval(approval);
        store.append(new Model.Activity(clock.instant().toString(), who.identity().name(), who.identity().kind(), "approval.request",
                build.id(), "ok", Map.of("approval", approval.id())));
        return approval;
    }

    /** A person decides; approving a release releases the build. */
    public Model.Approval decide(String id, boolean approve, String comment, Access.Principal who) {
        if (!who.identity().kind().equals("person")) {
            throw new ApiException(403, "FORBIDDEN", "only people decide approvals");
        }
        Model.Approval approval = store.approvals().stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("approval " + id));
        if (!approval.status().equals("pending")) {
            throw new ApiException(409, "CONFLICT", "approval " + id + " was already " + approval.status());
        }
        if (approve && approval.action().equals("build.release")) {
            Model.Build build = store.build(approval.target()).orElseThrow(() -> ApiException.notFound("build " + approval.target()));
            builds.release(build, who.identity().name(), who.identity().kind());
        }
        Model.Approval decided = new Model.Approval(approval.id(), approval.action(), approval.target(), approval.requestedBy(),
                approval.requestedAt(), approve ? "approved" : "rejected", who.identity().name(), clock.instant().toString(), comment);
        store.saveApproval(decided);
        store.append(new Model.Activity(clock.instant().toString(), who.identity().name(), who.identity().kind(), "approval.decide",
                approval.target(), "ok", Map.of("approval", id, "decision", decided.status())));
        return decided;
    }
}
