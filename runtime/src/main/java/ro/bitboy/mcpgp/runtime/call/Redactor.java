package ro.bitboy.mcpgp.runtime.call;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces every known secret with {@code ***}: the config's secret values plus secrets learned at
 * runtime (OAuth access tokens).
 */
final class Redactor {

    /** Shorter values would replace ordinary text. */
    private static final int MIN_LENGTH = 4;

    private final Set<String> secrets = ConcurrentHashMap.newKeySet();
    private volatile List<String> ordered = List.of();

    Redactor(Set<String> initial) {
        initial.forEach(this::add);
    }

    void add(String secret) {
        if (secret != null && secret.length() >= MIN_LENGTH && secrets.add(secret)) {
            // longest first, so a secret containing another is replaced whole
            ordered = secrets.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        }
    }

    String redact(String text) {
        String result = text;
        for (String secret : ordered) {
            result = result.replace(secret, "***");
        }
        return result;
    }
}
