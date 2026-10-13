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
    /** Credentials the client sent with the call being handled on this thread (auth.md §2.4): masked, never kept. */
    private static final ThreadLocal<List<String>> CALL = new ThreadLocal<>();
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

    /** Masks {@code values} in everything redacted on this thread until the returned handle is closed. */
    static AutoCloseable during(List<String> values) {
        List<String> masked = new java.util.ArrayList<>();
        for (String value : values) {
            if (value != null && value.length() >= MIN_LENGTH) {
                masked.add(value);
                int space = value.indexOf(' ');
                if (space > 0 && value.length() - space - 1 >= MIN_LENGTH) {
                    masked.add(value.substring(space + 1)); // "Bearer abc…": the token alone, as an API may echo it
                }
            }
        }
        masked.sort(Comparator.comparingInt(String::length).reversed());
        List<String> previous = CALL.get();
        CALL.set(masked);
        return () -> {
            if (previous == null) {
                CALL.remove();
            } else {
                CALL.set(previous);
            }
        };
    }

    String redact(String text) {
        String result = text;
        List<String> call = CALL.get();
        if (call != null && result != null) {
            for (String secret : call) {
                result = result.replace(secret, "***");
            }
        }
        for (String secret : ordered) {
            result = result.replace(secret, "***");
        }
        return result;
    }
}
