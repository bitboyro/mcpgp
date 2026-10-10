package ro.bitboy.mcpgp.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secrets in the platform config are references, never values (spec/platform-api.md §7):
 * {@code ${env:NAME}} or {@code ${file:/path}}.
 */
public final class Secrets {

    private static final Pattern REFERENCE = Pattern.compile("^\\$\\{(env|file):([^}]+)}$");

    private Secrets() {
    }

    public static boolean isReference(String value) {
        return value != null && REFERENCE.matcher(value).matches();
    }

    /** The value a reference points to; empty when it can't be read or is blank. */
    public static Optional<String> resolve(String reference, Map<String, String> env) {
        Matcher ref = REFERENCE.matcher(reference == null ? "" : reference);
        if (!ref.matches()) {
            throw new IllegalArgumentException("a secret must be a reference (${env:…} or ${file:…})");
        }
        String text;
        if (ref.group(1).equals("env")) {
            text = env.get(ref.group(2));
        } else {
            try {
                text = Files.readString(Path.of(ref.group(2))).strip();
            } catch (IOException e) {
                text = null;
            }
        }
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
    }
}
