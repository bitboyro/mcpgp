package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.packager.Minisign;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** `setup` and `check` (docs/guides/self-hosting.md): the folder to run the image with, and what would go wrong. */
class SetupTest {

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @TempDir
    Path dir;
    String output;

    int setup(String input, String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String[] all = new String[args.length + 3];
        all[0] = "setup";
        all[1] = "--out";
        all[2] = dir.toString();
        System.arraycopy(args, 0, all, 3, args.length);
        int code = new Setup(Map.of("MCPGP_PLATFORM_IMAGE", "docker.io/acme/mcpgp-platform:9.9.9"),
                input == null ? null : new BufferedReader(new StringReader(input)), new PrintStream(bytes, true, StandardCharsets.UTF_8)).run(all);
        output = bytes.toString(StandardCharsets.UTF_8);
        return code;
    }

    @Test
    void writesEverythingToRunThePublishedImage() throws Exception {
        assertThat(setup(null, "--yes", "--public-url", "https://mcpgp.acme.example/", "--workspace", "acme")).as(output).isZero();

        JsonNode compose = YAML.readTree(dir.resolve("compose.yaml").toFile());
        assertThat(compose.at("/services/platform/build").isMissingNode()).isTrue();      // no repository on the server
        assertThat(compose.at("/services/platform/image").asText()).contains("MCPGP_IMAGE");
        assertThat(Files.readString(dir.resolve("compose.yaml"))).startsWith("# Written by `setup` of docker.io/acme/mcpgp-platform:9.9.9");
        assertThat(dir.resolve("compose.postgres.yaml")).exists();
        assertThat(dir.resolve("compose.signing.yaml")).exists();
        assertThat(dir.resolve("compose.oidc.yaml")).doesNotExist();

        String env = Files.readString(dir.resolve(".env"));
        assertThat(env).contains("MCPGP_IMAGE=docker.io/acme/mcpgp-platform:9.9.9")
                .contains("COMPOSE_FILE=compose.yaml:compose.postgres.yaml:compose.signing.yaml")
                .contains("MCPGP_PUBLIC_URL=https://mcpgp.acme.example\n").contains("MCPGP_WORKSPACE=acme").contains("MCPGP_BIND=127.0.0.1");
        assertThat(env).doesNotContain("# MCPGP_IMAGE");
        assertThat(Files.readString(dir.resolve(".gitignore"))).contains("secrets/").contains(".env");

        Path secrets = dir.resolve("secrets");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(secrets))).isEqualTo("rwx------");
        for (String name : new String[] {"admin_password", "db_password", "signing_key"}) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(secrets.resolve(name)))).isEqualTo("rw-r--r--");
        }
        String admin = Files.readString(secrets.resolve("admin_password")).strip();
        assertThat(admin).hasSizeGreaterThanOrEqualTo(24);
        assertThat(output).doesNotContain(admin);                                           // never printed
        Minisign.readSecretKey(Files.readString(secrets.resolve("signing_key")));
        assertThat(dir.resolve("signing_key.pub")).exists();

        // a second run keeps everything
        assertThat(setup(null, "--yes")).isZero();
        assertThat(Files.readString(secrets.resolve("admin_password")).strip()).isEqualTo(admin);
        assertThat(output).contains("kept    compose.yaml").contains("kept    secrets/admin_password");
    }

    @Test
    void asksForWhatIsntGivenAndAddsCompanySignIn() throws Exception {
        String answers = String.join("\n", "https://mcpgp.acme.example", "acme", "files", "n", "https://login.acme.example/realms/acme", "mcpgp", "y") + "\n";
        assertThat(setup(answers)).as(output).isZero();
        assertThat(output).contains("Public address").contains("OIDC issuer").contains("todo    secrets/oidc_client_secret");
        assertThat(dir.resolve("compose.postgres.yaml")).doesNotExist();
        assertThat(dir.resolve("compose.signing.yaml")).doesNotExist();
        assertThat(dir.resolve("compose.oidc.yaml")).exists();
        assertThat(Files.readString(dir.resolve(".env"))).contains("COMPOSE_FILE=compose.yaml:compose.oidc.yaml");
        JsonNode platform = YAML.readTree(dir.resolve("platform.yaml").toFile());
        assertThat(platform.at("/auth/oidc/issuer").asText()).isEqualTo("https://login.acme.example/realms/acme");
        assertThat(platform.at("/auth/oidc/clientSecret").asText()).isEqualTo("${file:/run/secrets/oidc_client_secret}");
        assertThat(platform.at("/auth/oidc/roles").size()).isPositive();
        // the result is a config the platform accepts
        PlatformConfig.load(dir.resolve("platform.yaml"), Map.of());
    }

    @Test
    void upgradeChangesOnlyTheComposeFilesAndTheImage() throws Exception {
        assertThat(setup(null, "--yes", "--no-sign")).isZero();
        String password = Files.readString(dir.resolve("secrets/db_password"));
        Files.writeString(dir.resolve("compose.yaml"), "# changed by hand\n");
        Files.writeString(dir.resolve("platform.yaml"), Files.readString(dir.resolve("platform.yaml")) + "# mine\n");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = new Setup(Map.of("MCPGP_PLATFORM_IMAGE", "docker.io/acme/mcpgp-platform:10.0.0"), null,
                new PrintStream(bytes, true, StandardCharsets.UTF_8)).run(new String[] {"setup", "--out", dir.toString(), "--upgrade"});
        assertThat(code).as(bytes.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(Files.readString(dir.resolve("compose.yaml"))).contains("services:").contains("10.0.0");
        assertThat(Files.readString(dir.resolve(".env"))).contains("MCPGP_IMAGE=docker.io/acme/mcpgp-platform:10.0.0").doesNotContain("9.9.9");
        assertThat(Files.readString(dir.resolve("platform.yaml"))).endsWith("# mine\n");
        assertThat(Files.readString(dir.resolve("secrets/db_password"))).isEqualTo(password);
    }

    @Test
    void refusesWhatWouldntWork() {
        assertThat(setup(null, "--yes", "--store", "s3")).isEqualTo(2);
        assertThat(output).contains("postgres or files");
        assertThat(setup(null, "--yes", "--public-url", "mcpgp.acme.example")).isEqualTo(2);
        assertThat(setup(null, "--bogus")).isEqualTo(2);
    }

    @Test
    void checkSaysWhatWouldFailBeforeStarting() throws Exception {
        Path password = dir.resolve("admin_password");
        Files.writeString(password, "a long first password\n");
        PlatformConfig good = new PlatformConfig(dir.resolve("store"), "127.0.0.1", 8443).withAdmin(new PlatformConfig.Admin("admin", "${file:" + password + "}"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThat(new Check(good, Map.of(), new PrintStream(bytes, true, StandardCharsets.UTF_8)).run()).isZero();
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("✓ admin.password: readable").contains("✓ store: files").contains("! signing.key is not set")
                .contains("✓ ready to start");

        PlatformConfig bad = new PlatformConfig(dir.resolve("store"), "127.0.0.1", 8443, "${file:" + dir.resolve("missing.key") + "}",
                PlatformConfig.Retention.DEFAULT).withAdmin(new PlatformConfig.Admin("admin", "${env:NOT_SET}"))
                .withPostgres(new PlatformConfig.Postgres("jdbc:postgresql://127.0.0.1:1/mcpgp", "mcpgp", null, "mcpgp", 2));
        bytes.reset();
        assertThat(new Check(bad, Map.of(), new PrintStream(bytes, true, StandardCharsets.UTF_8)).run()).isEqualTo(1);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("✗ admin.password").contains("✗ signing.key").contains("✗ store: PostgreSQL")
                .contains("3 problems");
    }

    @Test
    void theConfigFileComesFromTheFlagThenTheEnvironment() {
        assertThat(PlatformConfig.configFile(new String[] {"check", "--config", "a.yaml"}, Map.of("MCPGP_PLATFORM_CONFIG", "b.yaml")))
                .isEqualTo(Path.of("a.yaml"));
        assertThat(PlatformConfig.configFile(new String[] {"check"}, Map.of("MCPGP_PLATFORM_CONFIG", "/etc/mcpgp/platform.yaml")))
                .isEqualTo(Path.of("/etc/mcpgp/platform.yaml"));
    }
}
