package ro.bitboy.mcpgp.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Operation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Steps 1–4 and 7–8 of the naming pipeline (naming.md §1): source, normalize, shape, vocabulary,
 * namespace and limits. Rules (5–6), collisions (9) and the lock file (10) are applied by the planner.
 * Internally names are lists of lowercase words; casing is applied at the end.
 */
final class Naming {

    /** The steps a name went through, for provenance. */
    record Step(String step, String value) {
    }

    private static final Set<String> VERBS = Set.of(
            "get", "list", "create", "update", "delete", "search", "find", "query", "add", "remove", "set",
            "put", "patch", "post", "retrieve", "fetch", "read", "modify", "change", "replace", "upload",
            "download", "send", "resend", "refund", "cancel", "approve", "reject", "start", "stop", "enable",
            "disable", "validate", "check", "count", "export", "import", "sync", "test", "erase", "new",
            "archive", "restore", "revoke", "release", "publish", "unpublish", "assign", "unassign", "grant", "activate",
            "deactivate", "lock", "unlock", "move", "copy", "clone", "duplicate", "merge", "rename", "submit", "close", "open",
            "reset", "verify", "refresh", "accept", "decline", "execute", "run", "trigger", "schedule", "register",
            "unregister", "subscribe", "unsubscribe", "attach", "detach", "link", "unlink", "generate", "deploy", "edit");

    private final String source;
    private final String fallback;
    private final List<String> split;
    private final Set<String> dropWords;
    private final String order;
    private final String casing;
    private final boolean singular;
    private final Map<Pattern, String> vocabulary = new LinkedHashMap<>();
    /** The verb for each HTTP method when the name comes from method and path; {@code LIST} is GET on a collection. */
    private final Map<String, String> methodVerbs = new LinkedHashMap<>(Map.of(
            "GET", "get", "LIST", "list", "POST", "create", "PUT", "replace", "PATCH", "update", "DELETE", "delete"));
    private final String serverPrefix;
    private final String serverSuffix;
    private final boolean perApi;
    final String profile;
    final String onTooLong;
    final String collisions;

    Naming(JsonNode policy) {
        source = policy.path("source").asText("operationId");
        fallback = policy.path("fallback").asText("methodPath");
        List<String> s = new ArrayList<>();
        policy.path("normalize").path("split").forEach(n -> s.add(n.asText()));
        split = s.isEmpty() ? List.of("camel", "pascal", "kebab", "dot", "slash") : List.copyOf(s);
        List<String> drop = new ArrayList<>();
        policy.path("normalize").path("dropWords").forEach(n -> drop.add(n.asText().toLowerCase(Locale.ROOT)));
        dropWords = Set.copyOf(drop);
        order = policy.path("shape").path("order").asText("object_verb");
        casing = policy.path("shape").path("case").asText("snake");
        singular = policy.path("shape").path("number").asText("singular").equals("singular");
        policy.path("vocabulary").fields().forEachRemaining(f -> {
            if (!f.getValue().isNull()) {         // null switches off an inherited entry
                vocabulary.put(Pattern.compile("(?<=^|_)(?:" + f.getKey() + ")(?=_|$)"), f.getValue().asText());
            }
        });
        policy.path("methodVerbs").fields().forEachRemaining(f -> {
            if (f.getValue().isTextual()) {
                methodVerbs.put(f.getKey().toUpperCase(Locale.ROOT), f.getValue().asText().toLowerCase(Locale.ROOT));
            }
        });
        serverPrefix = policy.path("namespace").path("server").asText("");
        serverSuffix = policy.path("namespace").path("suffix").asText("");
        perApi = policy.path("namespace").path("perApi").asBoolean(true);
        profile = policy.path("limits").path("profile").asText("strict");
        onTooLong = policy.path("limits").path("onTooLong").asText("shorten-then-hash");
        collisions = policy.path("collisions").asText("error");
    }

    /** Steps 1–4: the base name (snake case, without prefix) and the steps that produced it. */
    String base(Operation op, List<Step> steps) {
        String raw = sourceText(op, source);
        String used = source;
        if (raw == null || raw.isBlank()) {
            raw = sourceText(op, fallback);
            used = fallback;
        }
        steps.add(new Step("source", raw + (used.equals(source) ? "" : " (fallback " + used + ")")));

        List<String> words = used.equals("methodPath") ? methodPathWords(op) : words(raw);
        words.removeIf(dropWords::contains);
        steps.add(new Step("normalize", String.join(" ", words)));

        List<String> shaped = shape(words);
        String name = String.join("_", shaped);
        steps.add(new Step("shape", name));

        String vocab = name;
        for (Map.Entry<Pattern, String> entry : vocabulary.entrySet()) {
            vocab = entry.getKey().matcher(vocab).replaceAll(Matcher.quoteReplacement(entry.getValue()));
        }
        if (!vocab.equals(name)) {
            steps.add(new Step("vocabulary", vocab));
        }
        return vocab;
    }

    /** Step 7: prefixes. */
    String namespace(String name, String apiPrefix, boolean severalApis) {
        String result = name;
        if ((perApi || severalApis) && apiPrefix != null && !apiPrefix.isEmpty()) {
            result = apiPrefix + "_" + result;
        }
        if (!serverPrefix.isEmpty()) {
            result = serverPrefix + "_" + result;
        }
        if (!serverSuffix.isEmpty()) {
            result = result + "_" + serverSuffix;
        }
        return result;
    }

    /** Casing, applied to the full name (with prefixes and suffixes). */
    String cased(String snake) {
        return switch (casing) {
            case "camel" -> {
                String[] parts = snake.split("_");
                StringBuilder out = new StringBuilder(parts[0]);
                for (int i = 1; i < parts.length; i++) {
                    if (!parts[i].isEmpty()) {
                        out.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
                    }
                }
                yield out.toString();
            }
            case "kebab" -> snake.replace('_', '-');
            default -> snake;
        };
    }

    /** Step 8: the allowed characters and length. Returns null when the name is too long and may not be shortened. */
    String limit(String name) {
        boolean strict = profile.equals("strict");
        int max = strict ? 64 : 128;
        String cleaned = strict
                ? name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_")
                : name.replaceAll("[^A-Za-z0-9_.-]+", "_");
        cleaned = cleaned.replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (cleaned.length() <= max) {
            return cleaned;
        }
        if (onTooLong.equals("error")) {
            return null;
        }
        String hash = shortHash(cleaned);
        return cleaned.substring(0, max - hash.length() - 1).replaceAll("_$", "") + "_" + hash;
    }

    // ---------------------------------------------------------------------- helpers

    private static String sourceText(Operation op, String kind) {
        return switch (kind) {
            case "operationId" -> op.operationId();
            case "tagSummary" -> op.summary() == null ? null : (op.tags().isEmpty() ? "" : op.tags().get(0) + " ") + op.summary();
            default -> op.method() + " " + op.path();
        };
    }

    /** Splits on the configured boundaries and lowercases; collapses repeated words. */
    List<String> words(String text) {
        String t = text;
        if (split.contains("camel") || split.contains("pascal")) {
            t = t.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        }
        if (split.contains("kebab")) {
            t = t.replace('-', ' ');
        }
        if (split.contains("dot")) {
            t = t.replace('.', ' ');
        }
        if (split.contains("slash")) {
            t = t.replace('/', ' ');
        }
        t = t.replace('_', ' ');
        List<String> words = new ArrayList<>();
        for (String w : t.trim().split("[^A-Za-z0-9]+")) {
            String lower = w.toLowerCase(Locale.ROOT);
            if (!lower.isEmpty() && (words.isEmpty() || !words.get(words.size() - 1).equals(lower))) {
                words.add(lower);
            }
        }
        return words;
    }

    /** {@code GET /customers/{id}} → get customer; {@code GET /customers} → list customers; etc. */
    private List<String> methodPathWords(Operation op) {
        List<String> segments = new ArrayList<>();
        boolean endsWithParameter = false;
        for (String segment : op.path().split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            endsWithParameter = segment.startsWith("{");
            if (!endsWithParameter) {
                segments.addAll(words(segment));
            }
        }
        String method = op.method().equals("GET") && !endsWithParameter ? "LIST" : op.method();
        String verb = methodVerbs.getOrDefault(method, op.method().toLowerCase(Locale.ROOT));
        List<String> words = new ArrayList<>();
        words.add(verb);
        words.addAll(segments);
        return words;
    }

    private List<String> shape(List<String> words) {
        List<String> result = new ArrayList<>(words);
        boolean hasVerb = !result.isEmpty() && (VERBS.contains(result.get(0)) || methodVerbs.containsValue(result.get(0)));
        if (singular) {
            for (int i = hasVerb ? 1 : 0; i < result.size(); i++) {
                result.set(i, singularize(result.get(i)));
            }
        }
        if (hasVerb && order.equals("object_verb") && result.size() > 1) {
            result.add(result.remove(0));
        }
        return result;
    }

    static String singularize(String word) {
        if (word.length() <= 3 || word.endsWith("ss") || word.endsWith("us") || word.endsWith("is")) {
            return word;
        }
        if (word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("sses") || word.endsWith("xes") || word.endsWith("ches") || word.endsWith("shes")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("s")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
