package ro.bitboy.mcpgp.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.platform.store.FileStore;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SystemServersTest {

    @TempDir
    Path dir;

    @Test
    void madeAtFirstStartAndLeftAloneAtTheNext() {
        Store store = new FileStore(dir);
        assertThat(SystemServers.ensure(store, true)).isEqualTo("created");
        assertThat(SystemServers.ensure(store, true)).isEqualTo("unchanged");
        Model.Server server = store.server(SystemServers.PLATFORM).orElseThrow();
        assertThat(server.system()).isTrue();
        assertThat(server.versions()).singleElement().satisfies(v -> assertThat(v.author()).isEqualTo("system"));
        assertThat(store.version(server.latest().digest()).orElseThrow().root()).isEqualTo("mcpgp-platform.mcpgp.yaml");
    }

    @Test
    void anUpgradeAddsOneVersion() {
        Store store = new FileStore(dir);
        // what an earlier release left: a different project
        Model.Version older = new Projects(store).store(Map.of("mcpgp-platform.mcpgp.yaml",
                "apiVersion: mcpgp/v1\nkind: McpServer\nmetadata:\n  name: mcpgp-platform\n".getBytes(StandardCharsets.UTF_8)));
        store.saveServer(new Model.Server(SystemServers.PLATFORM, "mcpgp platform", null, false, "t", "t",
                List.of(new Model.VersionRef(1, older.digest(), "mcpgp 0.2.6", "system", "t")), 0, true), -1);

        assertThat(SystemServers.ensure(store, true)).isEqualTo("updated");
        assertThat(store.server(SystemServers.PLATFORM).orElseThrow().versions()).extracting(Model.VersionRef::number).containsExactly(1, 2);
        assertThat(SystemServers.ensure(store, true)).isEqualTo("unchanged");
    }

    @Test
    void aServerSomeoneElseCalledThatIsLeftAlone() {
        Store store = new FileStore(dir);
        Model.Version theirs = new Projects(store).store(Map.of("mine.mcpgp.yaml",
                "apiVersion: mcpgp/v1\nkind: McpServer\nmetadata:\n  name: mine\n".getBytes(StandardCharsets.UTF_8)));
        store.saveServer(new Model.Server(SystemServers.PLATFORM, "mine", null, false, "t", "t",
                List.of(new Model.VersionRef(1, theirs.digest(), null, "ana", "t")), 0), -1);

        assertThat(SystemServers.ensure(store, true)).isEqualTo("skipped");
        Model.Server server = store.server(SystemServers.PLATFORM).orElseThrow();
        assertThat(server.system()).isFalse();
        assertThat(server.latest().digest()).isEqualTo(theirs.digest());
    }

    @Test
    void turnedOffItIsArchivedAndTurnedOnItComesBack() {
        Store store = new FileStore(dir);
        assertThat(SystemServers.ensure(store, false)).isEqualTo("skipped");
        assertThat(store.server(SystemServers.PLATFORM)).isEmpty();

        SystemServers.ensure(store, true);
        assertThat(SystemServers.ensure(store, false)).isEqualTo("archived");
        assertThat(store.server(SystemServers.PLATFORM).orElseThrow().archived()).isTrue();
        assertThat(SystemServers.ensure(store, true)).isEqualTo("restored");
        assertThat(store.server(SystemServers.PLATFORM).orElseThrow().archived()).isFalse();
    }
}
