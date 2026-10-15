package ro.bitboy.mcpgp.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.packager.Minisign;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** `.mcpgp/config.yaml` (cli.md §1–2): `--all`, the version pin, defaults; `sign` and `verify` of a release directory. */
class RepoConfigTest {

    static final Path PIPELINE = Path.of("..", "examples", "pipeline").toAbsolutePath().normalize();

    @TempDir
    Path dir;
    String out;
    String err;

    int run(String... args) {
        StringWriter o = new StringWriter();
        StringWriter e = new StringWriter();
        int code = Main.run(new PrintWriter(o, true), new PrintWriter(e, true), args);
        out = o.toString();
        err = e.toString();
        return code;
    }

    @AfterEach
    void reset() {
        Main.cwd = Path.of("");
        Main.env = System.getenv();
    }

    Path repo(String cli) throws Exception {
        Path root = dir.resolve("repo");
        Files.createDirectories(root.resolve(".mcpgp"));
        Files.writeString(root.resolve(".mcpgp/config.yaml"), """
                cli: %s
                workspace: %s
                servers:
                  - %s
                out: out
                """.formatted(cli, PIPELINE.resolve("../platform/workspace").normalize(),
                PIPELINE.resolve("../platform/orders-readonly/orders-readonly.mcpgp.yaml").normalize()));
        return root;
    }

    @Test
    void allMeansTheServersOfTheRepositoryConfig() {
        Main.cwd = PIPELINE.resolve("..");      // not inside the example repo: no config found
        assertThat(run("validate", "--all")).isEqualTo(Main.USAGE);
        assertThat(err).contains("--all needs a repository config");

        Main.cwd = PIPELINE;
        assertThat(run("validate", "--all")).as(err).isZero();
        assertThat(out).contains("orders.mcpgp.yaml: ok").contains("orders-readonly.mcpgp.yaml: ok");

        // the same from a subfolder, and with --config from anywhere
        Main.cwd = PIPELINE.resolve(".github");
        assertThat(run("validate", "--all", "--quiet")).as(err).isZero();
        Main.cwd = dir;
        assertThat(run("validate", "--all", "--quiet", "--config", PIPELINE.resolve(".mcpgp/config.yaml").toString())).as(err).isZero();

        Main.cwd = PIPELINE;
        assertThat(run("validate", "--all", PIPELINE.resolve("x.mcpgp.yaml").toString())).isEqualTo(Main.USAGE);
        assertThat(run("validate")).isEqualTo(Main.USAGE);
        assertThat(err).contains("give a definition");
    }

    @Test
    void theCliVersionIsPinned() throws Exception {
        Main.cwd = repo("0.0.1");
        assertThat(run("validate", "--all")).isEqualTo(Main.VERSION_MISMATCH);
        assertThat(err).contains("pins mcpgp 0.0.1, this is " + Compiler.VERSION);
        assertThat(run("validate", "--all", "--quiet", "--ignore-version")).as(err).isZero();
        assertThat(run("--help")).isZero();       // help never checks
        assertThat(run("keygen", "--help")).isZero();

        Files.writeString(Main.cwd.resolve(".mcpgp/config.yaml"), "cli: 0.1.0\n");    // servers missing
        assertThat(run("validate", "--all")).isEqualTo(Main.ERRORS);
        assertThat(err).contains("servers");
    }

    @Test
    void buildAllWritesToOutAndSignVerifiesTheDirectory() throws Exception {
        Path root = repo(Compiler.VERSION);
        Main.cwd = root;
        Minisign.Keys keys = Minisign.generate();
        Path pub = dir.resolve("mcpgp.pub");
        Files.writeString(pub, Minisign.publicKeyFile(keys));
        Main.env = Map.of("SOURCE_DATE_EPOCH", "1791542400", "MCPGP_SIGNING_KEY", Minisign.secretKeyFile(keys));

        assertThat(run("build", "--all", "--quiet")).as(err).isZero();
        Path dist = root.resolve("out");
        assertThat(dist.resolve("orders-readonly-2.0.0.zip")).exists();

        assertThat(run("sign", dist.toString())).as(err).isZero();
        assertThat(out).contains("signed 2 files");
        assertThat(dist.resolve("SHA256SUMS")).content().contains("orders-readonly-2.0.0.zip").contains("orders-readonly-2.0.0.manifest.json");
        assertThat(run("verify", dist.toString(), "--key", pub.toString())).isZero();
        assertThat(out).contains("✓ all 2 files match");

        Files.writeString(dist.resolve("extra.txt"), "not signed");
        assertThat(run("verify", dist.toString(), "--key", pub.toString())).isEqualTo(Main.ERRORS);
        assertThat(out).contains("extra.txt: not listed");

        Main.env = Map.of();
        assertThat(run("sign", dist.toString())).isEqualTo(Main.ERRORS);
        assertThat(err).contains("MCPGP_SIGNING_KEY is not set");
        assertThat(run("sign", dist.toString(), "--key", "the-key-itself")).isEqualTo(Main.ERRORS);
        assertThat(err).contains("must be a reference");
    }
}
