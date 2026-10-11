package ro.bitboy.mcpgp.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** `mcpgp names accept` (naming.md §7): renames on purpose, and locking names the file doesn't have yet. */
class NamesAcceptTest {

    static final Path EXAMPLES = Path.of("..", "examples", "platform").toAbsolutePath().normalize();

    final StringWriter out = new StringWriter();
    final StringWriter err = new StringWriter();

    int run(String... args) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        return Main.run(new PrintWriter(out, true), new PrintWriter(err, true), args);
    }

    static Path copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path f : files.toList()) {
                Path target = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(f, target);
                }
            }
        }
        return to;
    }

    @Test
    void acceptsARenameAndLocksTheRest(@TempDir Path dir) throws IOException {
        Path project = copy(EXAMPLES.resolve("orders"), dir.resolve("orders"));
        String workspace = EXAMPLES.resolve("workspace").toString();
        Path definition = project.resolve("orders.mcpgp.yaml");
        Path lock = project.resolve("mcp-names.lock");
        // a name released earlier that the policy would now give differently
        Files.writeString(lock, Files.readString(lock).replace("name: orders_customer_update, ", "name: orders_customer_modify, "));
        assertThat(run("validate", "--workspace", workspace, definition.toString())).isZero();
        assertThat(out.toString()).contains("kept 'orders_customer_modify'");

        assertThat(run("names", "accept", "--workspace", workspace, definition.toString(), "orders_customer_modify")).as(err.toString()).isZero();
        assertThat(out.toString()).contains("renamed orders_customer_modify → orders_customer_update").contains("1 renamed, 0 added");
        assertThat(Files.readString(lock)).contains("name: orders_customer_update,").doesNotContain("orders_customer_modify")
                .doesNotContain("orders_account_list");                          // only what was asked

        assertThat(run("names", "accept", "--workspace", workspace, "--all", definition.toString())).isZero();
        assertThat(out.toString()).contains("locked orders_account_list").contains("0 renamed,");
        String written = Files.readString(lock);
        assertThat(written).startsWith("# Written by `mcpgp names accept`")
                .contains("{ id: \"orders:GET:/customers\",").contains("since: \"2.0.0\"");

        // the file loads back: no drift, nothing left to accept
        assertThat(run("validate", "--frozen-lock", "--workspace", workspace, definition.toString())).as(out.toString()).isZero();
        assertThat(out.toString()).doesNotContain("NAME_DRIFT");
        assertThat(run("names", "accept", "--workspace", workspace, "--all", definition.toString())).isZero();
        assertThat(out.toString()).contains("nothing to accept");
    }

    @Test
    void unknownToolsAndMissingArgumentsAreRefused(@TempDir Path dir) throws IOException {
        Path project = copy(EXAMPLES.resolve("orders"), dir.resolve("orders"));
        String definition = project.resolve("orders.mcpgp.yaml").toString();
        String workspace = EXAMPLES.resolve("workspace").toString();
        assertThat(run("names", "accept", "--workspace", workspace, definition)).isEqualTo(2);
        assertThat(err.toString()).contains("--all");
        assertThat(run("names", "accept", "--workspace", workspace, definition, "orders_nope")).isEqualTo(1);
        assertThat(err.toString()).contains("no tool named orders_nope");
    }
}
