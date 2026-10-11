package ro.bitboy.mcpgp.platform.store;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What every store does the same way (spec/platform-api.md §7.1); one subclass per store. */
abstract class StoreContract {

    abstract Store store();

    static Model.Server server(String id, String title) {
        return new Model.Server(id, title, null, false, "2026-10-11T10:00:00Z", "2026-10-11T10:00:00Z",
                List.of(new Model.VersionRef(1, "a".repeat(64), "first", "ana", "2026-10-11T10:00:00Z")), 0);
    }

    @Test
    void serversHaveRevisionsAndRefuseStaleWrites() {
        Store store = store();
        Model.Server created = store.saveServer(server("orders-mcp", "Orders"), -1);
        assertThat(created.revision()).isEqualTo(1);
        assertThatThrownBy(() -> store.saveServer(server("orders-mcp", "Again"), -1)).isInstanceOf(Store.ConflictException.class);

        Model.Server changed = store.saveServer(server("orders-mcp", "Orders API"), 1);
        assertThat(changed.revision()).isEqualTo(2);
        assertThatThrownBy(() -> store.saveServer(server("orders-mcp", "Stale"), 1)).isInstanceOf(Store.ConflictException.class);
        assertThat(store.server("orders-mcp").orElseThrow().title()).isEqualTo("Orders API");
        assertThat(store.server("orders-mcp").orElseThrow().versions()).hasSize(1);

        store.saveServer(server("billing-mcp", "Billing"), -1);
        assertThat(store.servers()).extracting(Model.Server::id).containsExactly("billing-mcp", "orders-mcp");
        assertThat(store.server("missing")).isEmpty();
        assertThatThrownBy(() -> store.server("../etc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void libraryItemsToo() {
        Store store = store();
        Model.LibraryItem item = new Model.LibraryItem("naming", "acme-standard", "Acme", "t", "t", List.of(), 0);
        assertThat(store.saveLibraryItem(item, -1).revision()).isEqualTo(1);
        assertThatThrownBy(() -> store.saveLibraryItem(item, 5)).isInstanceOf(Store.ConflictException.class);
        store.saveLibraryItem(new Model.LibraryItem("auth", "sso", "SSO", "t", "t", List.of(), 0), -1);
        assertThat(store.library()).extracting(Model.LibraryItem::name).containsExactly("sso", "acme-standard");
        assertThat(store.libraryItem("naming", "acme-standard").orElseThrow().title()).isEqualTo("Acme");
    }

    @Test
    void contentIsAddressedByItsDigest() {
        Store store = store();
        byte[] content = "openapi: 3.1.0\n".getBytes(StandardCharsets.UTF_8);
        String digest = store.putBlob(content);
        assertThat(digest).isEqualTo(FileStore.sha256(content));
        assertThat(store.putBlob(content)).isEqualTo(digest);
        assertThat(store.blob(digest)).isEqualTo(content);
        assertThatThrownBy(() -> store.blob("0".repeat(64))).hasMessageContaining("missing");

        Model.Version version = new Model.Version("b".repeat(64), "mcpgp.yaml", Map.of("mcpgp.yaml", digest));
        store.saveVersion(version);
        store.saveVersion(version);
        assertThat(store.version("b".repeat(64))).contains(version);
        assertThatThrownBy(() -> store.version("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildsKeepTheirFilesAndLogUntilDeleted() {
        Store store = store();
        Model.Build build = new Model.Build("b-abc123", "orders-mcp", 1, "a".repeat(64), "running", "ana", "person", "t", "t", null, 1,
                null, false, null, false, null, false, List.of(), null);
        store.saveBuild(build);
        store.saveBuild(new Model.Build("b-abc123", "orders-mcp", 1, "a".repeat(64), "succeeded", "ana", "person", "t", "t", "t", 1,
                "1.0.0+1", false, null, false, null, false, List.of(new Model.Artifact("orders.zip", 3, "x")), null));
        assertThat(store.build("b-abc123").orElseThrow().status()).isEqualTo("succeeded");
        assertThat(store.builds()).hasSize(1);

        store.writeBuildFile("b-abc123", "orders.zip", new byte[] {1, 2, 3});
        store.appendBuildLog("b-abc123", "compiled\n");
        store.appendBuildLog("b-abc123", "packaged\n");
        assertThat(store.buildFile("b-abc123", "orders.zip")).hasValueSatisfying(b -> assertThat(b).containsExactly(1, 2, 3));
        assertThat(new String(store.buildFile("b-abc123", "log.txt").orElseThrow(), StandardCharsets.UTF_8)).isEqualTo("compiled\npackaged\n");
        assertThat(store.buildFile("b-abc123", "other.zip")).isEmpty();
        assertThatThrownBy(() -> store.writeBuildFile("b-abc123", "../x", new byte[0])).isInstanceOf(IllegalArgumentException.class);

        store.deleteBuildFiles("b-abc123");
        assertThat(store.buildFile("b-abc123", "orders.zip")).isEmpty();
        assertThat(store.buildFile("b-abc123", "log.txt")).isEmpty();
        assertThat(store.build("b-abc123")).isPresent();
    }

    @Test
    void recordsAreReplacedAndASaveMovesThemLast() {
        Store store = store();
        store.saveIdentity(new Model.Identity("i-1", "person", "ana", Set.of("admin"), null, "t", false));
        store.saveIdentity(new Model.Identity("i-2", "agent", "bot", Set.of("servers:read"), "i-1", "t", false));
        store.saveIdentity(new Model.Identity("i-1", "person", "ana", Set.of("admin"), null, "t", true));
        assertThat(store.identities()).extracting(Model.Identity::id).containsExactly("i-2", "i-1");
        assertThat(store.identities().get(1).disabled()).isTrue();

        store.saveToken(new Model.Token("t-1", "i-1", "hash", Set.of("admin"), "cli", "t", null, false));
        store.saveCredential(new Model.Credential("i-1", "salt", 1000, "hash", 0, null));
        store.saveCredential(new Model.Credential("i-1", "salt", 1000, "hash", 2, null));
        store.saveApproval(new Model.Approval("a-1", "release", "orders-mcp", "bot", "t", "pending", null, null, null));
        assertThat(store.tokens()).hasSize(1);
        assertThat(store.credentials()).singleElement().extracting(Model.Credential::failures).isEqualTo(2);
        assertThat(store.approvals()).singleElement().extracting(Model.Approval::status).isEqualTo("pending");
    }

    @Test
    void activityComesNewestFirst() {
        Store store = store();
        for (int i = 0; i < 5; i++) {
            store.append(new Model.Activity("2026-10-11T10:00:0" + i + "Z", "ana", "person", i % 2 == 0 ? "server.create" : "build.start",
                    "orders-mcp", "ok", Map.of("n", i)));
        }
        assertThat(store.activity(a -> true, 3)).extracting(Model.Activity::ts)
                .containsExactly("2026-10-11T10:00:04Z", "2026-10-11T10:00:03Z", "2026-10-11T10:00:02Z");
        assertThat(store.activity(a -> a.action().equals("build.start"), 10)).hasSize(2);
        assertThat(store.activity(a -> true, 10).get(0).details()).containsEntry("n", 4);
    }

    @Test
    void theCacheIsOverwritten() {
        Store store = store();
        assertThat(store.cached("manifest", "abc")).isEmpty();
        store.cache("manifest", "abc", new byte[] {1});
        store.cache("manifest", "abc", new byte[] {2});
        assertThat(store.cached("manifest", "abc")).hasValueSatisfying(b -> assertThat(b).containsExactly(2));
        assertThatThrownBy(() -> store.cache("Manifest", "abc", new byte[0])).isInstanceOf(IllegalArgumentException.class);
        store.check();
        assertThat(store.describe()).isNotBlank();
    }
}
