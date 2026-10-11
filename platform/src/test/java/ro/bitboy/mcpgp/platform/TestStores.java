package ro.bitboy.mcpgp.platform;

import java.util.UUID;

/**
 * Which store the platform tests run on: the file store, or PostgreSQL when
 * {@code MCPGP_TEST_POSTGRES_URL} is set (CI runs the suite a second time that way). Each test class
 * gets a schema of its own.
 */
public final class TestStores {

    public static final String URL = System.getenv("MCPGP_TEST_POSTGRES_URL");

    private TestStores() {
    }

    public static PlatformConfig.Postgres postgres() {
        return new PlatformConfig.Postgres(URL, System.getenv().getOrDefault("MCPGP_TEST_POSTGRES_USER", "mcpgp"),
                System.getenv("MCPGP_TEST_POSTGRES_PASSWORD") == null ? null : "${env:MCPGP_TEST_POSTGRES_PASSWORD}",
                "t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), 4);
    }

    /** The config as given (the file store), or the same config on a fresh PostgreSQL schema. */
    public static PlatformConfig of(PlatformConfig config) {
        return URL == null ? config : config.withPostgres(postgres());
    }
}
