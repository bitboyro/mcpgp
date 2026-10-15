package ro.bitboy.mcpgp.platform.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.bitboy.mcpgp.platform.PlatformConfig;
import ro.bitboy.mcpgp.platform.TestStores;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs when {@code MCPGP_TEST_POSTGRES_URL} points to a database (CI starts one; locally: docs/process/development.md). */
class PostgresStoreTest extends StoreContract {

    PlatformConfig.Postgres pg;
    PostgresStore store;

    @BeforeEach
    void open() {
        assumeTrue(TestStores.URL != null, "MCPGP_TEST_POSTGRES_URL is not set");
        pg = TestStores.postgres();
        store = open(pg);
    }

    static PostgresStore open(PlatformConfig.Postgres pg) {
        return new PostgresStore(pg.url(), pg.user(), System.getenv("MCPGP_TEST_POSTGRES_PASSWORD"), pg.schema(), 2);
    }

    @AfterEach
    void close() {
        if (store != null) {
            store.close();
        }
    }

    @Override
    Store store() {
        return store;
    }

    @Test
    void oneProcessPerSchema() {
        assertThatThrownBy(() -> open(pg)).hasMessageContaining("Another platform process");
        store.close();
        store = open(pg);       // the lock went with the first store
        assertThat(store.servers()).isEmpty();
    }

    @Test
    void dataSurvivesReopening() {
        store.saveServer(server("orders-mcp", "Orders"), -1);
        store.close();
        store = open(pg);
        assertThat(store.server("orders-mcp")).isPresent();
        assertThat(store.describe()).isEqualTo("postgres (schema " + pg.schema() + ")");
    }

    @Test
    void refusesAnotherFormat() throws Exception {
        store.close();
        try (var c = DriverManager.getConnection(pg.url(), pg.user(), System.getenv("MCPGP_TEST_POSTGRES_PASSWORD"));
             var st = c.createStatement()) {
            st.execute("UPDATE " + pg.schema() + ".store_info SET value = '0.0' WHERE key = 'format'");
        }
        assertThatThrownBy(() -> open(pg)).hasMessageContaining("has format 0.0").hasMessageContaining("export");
        store = null;
    }
}
