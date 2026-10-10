package ro.bitboy.mcpgp.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.platform.service.Builds;
import ro.bitboy.mcpgp.platform.service.Compilation;
import ro.bitboy.mcpgp.platform.store.FileStore;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Retention (spec/platform-api.md §2.2): archive, then delete files; released builds and the last K stay. */
class RetentionTest {

    static Model.Build build(String id, Instant created, boolean released, boolean archived, Instant archivedAt) {
        return new Model.Build(id, "orders", 1, "0".repeat(64), "succeeded", "ana", "person", created.toString(), created.toString(),
                created.toString(), null, "2.0.0", released, released ? "ana" : null, archived, archivedAt == null ? null : archivedAt.toString(),
                false, List.of(new Model.Artifact("orders-mcp-2.0.0.zip", 1, "x")), null);
    }

    @Test
    void archivesOldBuildsThenDeletesTheirFilesButKeepsReleasedAndRecentOnes(@TempDir Path dir) {
        Store store = new FileStore(dir);
        Instant now = Instant.parse("2027-06-01T00:00:00Z");
        store.saveBuild(build("b-000001", now.minus(Duration.ofDays(200)), false, false, null));                 // old: archive
        store.saveBuild(build("b-000002", now.minus(Duration.ofDays(500)), false, true, now.minus(Duration.ofDays(400)))); // archived long ago: delete
        store.saveBuild(build("b-000003", now.minus(Duration.ofDays(300)), true, false, null));                  // released: never
        store.saveBuild(build("b-000004", now.minus(Duration.ofDays(100)), false, false, null));                 // among the last 2: kept
        store.saveBuild(build("b-000005", now.minus(Duration.ofDays(95)), false, false, null));                  // among the last 2: kept
        store.writeBuildFile("b-000002", "orders-mcp-2.0.0.zip", new byte[] {1});

        PlatformConfig config = new PlatformConfig(dir, "127.0.0.1", 0, null, new PlatformConfig.Retention(90, 365, 2));
        Builds builds = new Builds(store, new Compilation(store), config, Map.of(), Clock.fixed(now, ZoneOffset.UTC));
        builds.applyRetention();

        assertThat(store.build("b-000001").orElseThrow().archived()).isTrue();
        assertThat(store.build("b-000001").orElseThrow().deleted()).isFalse();
        assertThat(store.build("b-000002").orElseThrow().deleted()).isTrue();
        assertThat(store.buildFile("b-000002", "orders-mcp-2.0.0.zip")).isEmpty();
        assertThat(store.build("b-000003").orElseThrow().archived()).isFalse();
        assertThat(store.build("b-000004").orElseThrow().archived()).isFalse();
        assertThat(store.build("b-000005").orElseThrow().archived()).isFalse();
        assertThat(store.activity(a -> a.actor().equals("retention"), 10)).extracting(Model.Activity::action)
                .contains("build.archive", "build.delete");
        builds.close();
    }
}
