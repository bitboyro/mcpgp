package ro.bitboy.mcpgp.runtime.config;

import java.util.Locale;

/**
 * Names of the {@code .env} variables that setup commands create for secrets, e.g.
 * {@code PROD_EU_PASSWORD}. They never start with the server's own prefix ({@code ORDERS_MCP_}), which
 * is reserved for settings (runtime-config §1.1).
 */
public final class EnvNames {

    private EnvNames() {
    }

    public static String of(String serverName, String owner, String field) {
        String name = (owner + "_" + field).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        if (Character.isDigit(name.charAt(0)) || name.startsWith(EnvOverrides.prefix(serverName))) {
            name = "SECRET_" + name;
        }
        return name;
    }
}
