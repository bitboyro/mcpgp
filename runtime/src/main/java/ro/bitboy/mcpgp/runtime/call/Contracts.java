package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.bitboy.mcpgp.runtime.config.BaseUrl;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.mcp.ToolError;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which contract each instance uses (runtime-config §4.4). A pinned instance uses its version and the
 * target is never asked. An {@code auto} instance whose API has a detection rule asks its target once,
 * when the server starts or reloads: under each contract's base path in turn with {@code underBasePath}
 * (the preferred contract first, then the newest), and the first answer that names a version wins. The
 * request carries the instance's own credential when it has one; per-caller credentials (fromRequest,
 * tokenExchange) only exist with a call, so when the target refuses the request without credentials,
 * the first call asks again with that caller's credential. Calls wait for detection.
 */
public final class Contracts {

    private static final Logger LOG = LoggerFactory.getLogger(Contracts.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** How often a refused instance, or one waiting for a caller's credential, asks its target again. */
    static final Duration RETRY = Duration.ofMinutes(1);

    /**
     * An instance's contract.
     *
     * @param contract  the version calls use; null when the instance is unavailable
     * @param selection {@code pinned}, {@code detected}, or {@code default} (auto without a rule, or
     *                  detection failed with {@code useDefault})
     * @param reported  the version the target reported (the value read, before the pattern), when it was asked
     * @param reason    why detection didn't pick a contract; null when it did
     */
    public record Choice(String contract, String selection, String reported, String reason) {
        public boolean available() {
            return contract != null;
        }
    }

    /** One detection: the contract found, or why none was. */
    private record Outcome(String contract, String reported, String reason, boolean needsCaller) {
    }

    private final class Detection {
        final RuntimeConfig.Instance instance;
        final Manifest.Api api;
        volatile CompletableFuture<Outcome> outcome;
        volatile Instant asked;
        /** A call's own credential was used: the next ones wait for {@link #RETRY}. */
        volatile boolean callerAsked;

        Detection(RuntimeConfig.Instance instance, Manifest.Api api) {
            this.instance = instance;
            this.api = api;
        }
    }

    private final Manifest manifest;
    private final Upstream upstream;
    private final Clock clock;
    private final Map<String, Detection> detections = new ConcurrentHashMap<>();
    /**
     * With {@code mixedContracts: forbid}: the one version each API's instances use, the first pinned
     * one or the default; a target detected on another version is unavailable (runtime-config §7).
     */
    private final Map<String, String> onlyVersion = new ConcurrentHashMap<>();
    private volatile BiConsumer<RuntimeConfig.Instance, Choice> listener = (instance, choice) -> { };

    Contracts(Manifest manifest, List<RuntimeConfig.Instance> instances, Upstream upstream, Clock clock) {
        this.manifest = manifest;
        this.upstream = upstream;
        this.clock = clock;
        if (manifest.root().at("/instances/mixedContracts").asText("union").equals("forbid")) {
            for (RuntimeConfig.Instance instance : instances) {
                if (!instance.contract().equals("auto")) {
                    onlyVersion.putIfAbsent(instance.api(), instance.contract());
                }
            }
            manifest.apis().forEach((id, api) -> {
                if (api.defaultContract() != null) {
                    onlyVersion.putIfAbsent(id, api.defaultContract());
                }
            });
        }
        for (RuntimeConfig.Instance instance : instances) {
            Manifest.Api api = manifest.apis().get(instance.api());
            if (api != null && instance.contract().equals("auto") && api.node().path("detect").isObject()) {
                detections.put(key(instance), new Detection(instance, api));
            }
        }
    }

    /** How long starting waits for the answers; slower ones arrive in the background, and their calls wait. */
    static final Duration STARTUP_WAIT = Duration.ofSeconds(2);

    /** Starts detection for every auto instance with a rule, and waits up to {@link #STARTUP_WAIT} for the answers. */
    public void start() {
        List<CompletableFuture<Outcome>> all = new ArrayList<>();
        for (Detection d : detections.values()) {
            all.add(outcome(d));
        }
        try {
            CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).get(STARTUP_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            // still asking: calls to those instances wait for the answer
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Told each detection's result once it's known (log and audit). */
    public void onResult(BiConsumer<RuntimeConfig.Instance, Choice> listener) {
        this.listener = listener;
    }

    /** The contract an instance uses now, waiting for detection; never asks the target (instances_list). */
    public Choice of(RuntimeConfig.Instance instance) {
        Detection d = detections.get(key(instance));
        if (d == null) {
            return fixed(instance);
        }
        return choice(d, outcome(d).join());
    }

    /**
     * The contract a call uses: as {@link #of}, and when the target hasn't been reached with a
     * credential yet, or refused the instance, it is asked again (at most once a minute).
     *
     * @param sent the call's credentials, as {@link Upstream#credential} takes them
     */
    Choice forCall(RuntimeConfig.Instance instance, Map<String, String> sent) {
        Detection d = detections.get(key(instance));
        if (d == null) {
            return fixed(instance);
        }
        Outcome outcome = outcome(d).join();
        if (outcome.contract() != null) {
            return choice(d, outcome);
        }
        boolean due = clock.instant().isAfter(d.asked.plus(RETRY));
        boolean caller = outcome.needsCaller() || d.callerAsked; // the target wants a caller's credential
        if (outcome.needsCaller() && !d.callerAsked || due && (refuses(d) || caller)) {
            Upstream.Credential credential = null;
            if (caller) {
                try {
                    credential = Upstream.credential(instance, sent, false, null, d.api.upstreamAuth().path("schemes"));
                } catch (ToolError e) {
                    return choice(d, outcome); // this caller has nothing to send either
                }
            }
            outcome = again(d, outcome, credential, caller);
        }
        return choice(d, outcome);
    }

    private Outcome again(Detection d, Outcome seen, Upstream.Credential credential, boolean caller) {
        synchronized (d) {
            if (d.outcome.join() == seen) {
                d.callerAsked = caller;
                ask(d, credential);
            }
        }
        return d.outcome.join();
    }

    private CompletableFuture<Outcome> outcome(Detection d) {
        synchronized (d) {
            if (d.outcome == null) {
                ask(d, null);
            }
            return d.outcome;
        }
    }

    private static boolean refuses(Detection d) {
        return d.api.node().at("/detect/onFailure").asText("useDefault").equals("refuse");
    }

    /**
     * Asks a target which version it runs, now, whatever the instance's selection ({@code doctor}): the
     * version it reports and the contract that matches it, or why there's none.
     */
    public static Choice probe(Manifest manifest, RuntimeConfig.Instance instance) {
        Manifest.Api api = manifest.apis().get(instance.api());
        if (api == null || !api.node().path("detect").isObject()) {
            return new Choice(null, "none", null, "this build has no detection rule");
        }
        Outcome outcome = new Contracts(manifest, List.of(), new Upstream(apiKeys(manifest), new Redactor(Set.of())), Clock.systemUTC())
                .detect(instance, api, startupCredential(instance));
        return new Choice(outcome.contract(), "detected", outcome.reported(), outcome.reason());
    }

    // ------------------------------------------------------------------------------------------------

    private void ask(Detection d, Upstream.Credential credential) {
        d.asked = clock.instant();
        Upstream.Credential sent = credential != null ? credential : startupCredential(d.instance);
        d.outcome = CompletableFuture.supplyAsync(() -> detect(d.instance, d.api, sent), Thread::startVirtualThread)
                .exceptionally(e -> new Outcome(null, null, "detection failed: " + e.getMessage(), false))
                .whenComplete((outcome, e) -> {
                    Choice choice = choice(d, outcome);
                    if (choice.selection().equals("detected")) {
                        LOG.info("Instance {} runs contract {} (its target reports {})", d.instance.id(), choice.contract(), outcome.reported());
                    } else if (choice.available()) {
                        LOG.warn("Instance {} uses the default contract {}: {}", d.instance.id(), choice.contract(), outcome.reason());
                    } else {
                        LOG.warn("Instance {} is unavailable until its version is known: {}", d.instance.id(), outcome.reason());
                    }
                    try {
                        listener.accept(d.instance, choice);
                    } catch (RuntimeException failed) {
                        LOG.warn("Recording instance {}'s contract failed: {}", d.instance.id(), failed.getMessage());
                    }
                });
    }

    private Choice choice(Detection d, Outcome outcome) {
        String only = onlyVersion.get(d.api.id());
        if (outcome.contract() != null) {
            if (only != null && !only.equals(outcome.contract())) {
                return new Choice(null, "detected", outcome.reported(), "mixedContracts: forbid: the target runs " + outcome.contract()
                        + ", and this server's instances all use " + only);
            }
            return new Choice(outcome.contract(), "detected", outcome.reported(), null);
        }
        return new Choice(refuses(d) ? null : only != null ? only : d.api.defaultContract(), "default", outcome.reported(), outcome.reason());
    }

    private Choice fixed(RuntimeConfig.Instance instance) {
        return instance.contract().equals("auto")
                ? new Choice(manifest.apis().get(instance.api()).defaultContract(), "default", null, null)
                : new Choice(instance.contract(), "pinned", null, null);
    }

    /** The instance's own credential for the detection request: the first that isn't the caller's; none otherwise. */
    private static Upstream.Credential startupCredential(RuntimeConfig.Instance instance) {
        for (JsonNode entry : instance.authEntries()) {
            String type = entry.path("type").asText("none");
            if (!type.equals("fromRequest") && !type.equals("tokenExchange")) {
                return new Upstream.Credential(entry, null);
            }
        }
        return new Upstream.Credential(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("type", "none"), null);
    }

    private Outcome detect(RuntimeConfig.Instance instance, Manifest.Api api, Upstream.Credential credential) {
        JsonNode rule = api.node().path("detect");
        Pattern pattern;
        try {
            pattern = Pattern.compile(rule.path("pattern").asText());
        } catch (java.util.regex.PatternSyntaxException e) {
            return new Outcome(null, null, "the detection pattern is not a valid regular expression", false);
        }
        String method = rule.path("method").asText("GET");
        String reason = null;
        String reported = null;
        boolean refused = false;
        for (URI uri : addresses(instance, api, rule)) {
            RequestBuilder.UpstreamRequest request = new RequestBuilder.UpstreamRequest(method, uri, List.of(Map.entry("Accept", "application/json, text/plain, */*")),
                    method.equals("POST") ? new byte[0] : null, method.equals("POST") ? "application/json" : null);
            HttpResponse<byte[]> response;
            try {
                response = upstream.send(instance, request, credential, false);
            } catch (ToolError e) {
                return new Outcome(null, null, "the target didn't answer the version request: " + e.getMessage(), false);
            } catch (RuntimeException e) {
                return new Outcome(null, null, "the version request failed: " + e.getMessage(), false);
            }
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                refused = true;
                reason = uri.getRawPath() + " answered HTTP " + status;
                continue;
            }
            if (status >= 500) {
                return new Outcome(null, null, uri.getRawPath() + " answered HTTP " + status, false);
            }
            if (status >= 300) {
                reason = uri.getRawPath() + " answered HTTP " + status; // not here: maybe under another base path
                continue;
            }
            String value = value(response, rule);
            if (value == null) {
                reason = uri.getRawPath() + " answered without " + (rule.has("field") ? "the field " + rule.path("field").asText()
                        : "the header " + rule.path("header").asText());
                continue;
            }
            Matcher m = pattern.matcher(value);
            if (!m.find()) {
                reason = uri.getRawPath() + " answered '" + shorten(value) + "', which the detection pattern doesn't match";
                continue;
            }
            reported = shorten(value);
            String contract = shipped(api, m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group());
            return contract != null ? new Outcome(contract, reported, null, false)
                    : new Outcome(null, reported, "the target reports version " + reported + ", which this build doesn't ship (it has "
                    + String.join(", ", api.contracts().keySet()) + ")", false);
        }
        boolean callerCredentials = credential.type().equals("none") && instance.authEntries().stream()
                .anyMatch(e -> Set.of("fromRequest", "tokenExchange").contains(e.path("type").asText()));
        return new Outcome(null, reported, reason == null ? "the target didn't report a version" : reason, refused && callerCredentials);
    }

    /** Where to ask: the instance's address, or with {@code underBasePath} each contract's base path (preferred first, then newest). */
    private static List<URI> addresses(RuntimeConfig.Instance instance, Manifest.Api api, JsonNode rule) {
        String path = rule.path("path").asText("/");
        Set<URI> uris = new LinkedHashSet<>();
        if (rule.path("underBasePath").asBoolean(false)) {
            for (String version : order(instance, api)) {
                uris.add(join(BaseUrl.resolve(instance.baseUrl(), api.contracts().get(version).basePath()), path));
            }
        } else {
            uris.add(join(BaseUrl.resolve(instance.baseUrl(), null), path));
        }
        return List.copyOf(uris);
    }

    /** The instance's preferred contract (or the default), then the others, newest first. */
    static List<String> order(RuntimeConfig.Instance instance, Manifest.Api api) {
        List<String> versions = new ArrayList<>(api.contracts().keySet());
        versions.sort((a, b) -> compare(b, a));
        String first = instance.prefer() != null ? instance.prefer() : api.defaultContract();
        if (first != null && versions.remove(first)) {
            versions.add(0, first);
        }
        return versions;
    }

    /** The shipped contract a reported version means: the same version, or the longest one it starts with ({@code 2.0.3} → {@code 2.0}). */
    static String shipped(Manifest.Api api, String reported) {
        String best = null;
        for (String version : api.contracts().keySet()) {
            if ((reported.equals(version) || reported.startsWith(version + ".")) && (best == null || version.length() > best.length())) {
                best = version;
            }
        }
        return best;
    }

    private static String value(HttpResponse<byte[]> response, JsonNode rule) {
        if (rule.hasNonNull("header")) {
            return response.headers().firstValue(rule.path("header").asText()).orElse(null);
        }
        String body = new String(response.body() == null ? new byte[0] : response.body(), StandardCharsets.UTF_8);
        if (!rule.hasNonNull("field")) {
            return body.strip();
        }
        try {
            JsonNode value = JSON.readTree(body).at(rule.path("field").asText());
            return value.isValueNode() && !value.isNull() ? value.asText() : null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private static URI join(URI base, String path) {
        String prefix = base.toString();
        return URI.create((prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix) + (path.startsWith("/") ? path : "/" + path));
    }

    private static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0";
            String q = i < y.length ? y[i] : "0";
            int c = p.matches("\\d+") && q.matches("\\d+") ? Long.compare(Long.parseLong(p), Long.parseLong(q)) : p.compareTo(q);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static String shorten(String text) {
        String line = text.replaceAll("\\s+", " ");
        return line.length() <= 60 ? line : line.substring(0, 59) + "…";
    }

    private static String key(RuntimeConfig.Instance instance) {
        return instance.api() + "/" + instance.id();
    }

    static Map<String, JsonNode> apiKeys(Manifest manifest) {
        Map<String, JsonNode> apiKeys = new java.util.LinkedHashMap<>();
        manifest.apis().forEach((id, api) -> {
            JsonNode placement = api.upstreamAuth().path("apiKey");
            if (placement.isObject()) {
                apiKeys.put(id, placement);
            }
        });
        return apiKeys;
    }
}
