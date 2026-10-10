package ro.bitboy.mcpgp.runtime.mcp;

import java.security.SecureRandom;
import java.util.HexFormat;

/** Correlation ids: {@code c-} and 6 hex digits, in results, audit events and logs. */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String correlation() {
        byte[] bytes = new byte[3];
        RANDOM.nextBytes(bytes);
        return "c-" + HexFormat.of().formatHex(bytes);
    }
}
