package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import ro.bitboy.mcpgp.platform.store.FileStore;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Running the platform as a deployment: the store choice, the first administrator, warnings, health, copy-store. */
class DeploymentTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    Path config(String yaml) throws Exception {
        Path file = dir.resolve("platform.yaml");
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    void specHostsAreNoneByDefaultAndCredentialsAreReferences() throws Exception {
        assertThat(PlatformConfig.load(config("workspace: acme\n"), Map.of()).specHosts()).isEmpty();
        PlatformConfig hosts = PlatformConfig.load(config("""
                specHosts:
                  - api.acme.com
                  - { host: "https://specs.acme.com:8443/", header: Authorization, value: "${env:SPECS_TOKEN}" }
                  - http://build.acme.internal
                """), Map.of());
        assertThat(hosts.specHosts()).containsExactly(
                new PlatformConfig.SpecHost("api.acme.com", false, null, null),
                new PlatformConfig.SpecHost("specs.acme.com:8443", false, "Authorization", "${env:SPECS_TOKEN}"),
                new PlatformConfig.SpecHost("build.acme.internal", true, null, null));
        assertThatThrownBy(() -> PlatformConfig.load(config("specHosts: [ { host: api.acme.com, header: Authorization, value: Bearer-abc } ]\n"),
                Map.of())).hasMessageContaining("must be a reference");
        assertThatThrownBy(() -> PlatformConfig.load(config("specHosts: [ \"api.acme.com/specs\" ]\n"), Map.of()))
                .hasMessageContaining("not a host name");
        assertThatThrownBy(() -> PlatformConfig.load(config("specHosts: api.acme.com\n"), Map.of())).hasMessageContaining("a list of hosts");
    }

    @Test
    void storageIsFilesOrPostgres() throws Exception {
        PlatformConfig files = PlatformConfig.load(config("storage: { type: files, root: data }\n"), Map.of());
        assertThat(files.postgres()).isNull();
        assertThat(files.storageRoot()).isEqualTo(dir.resolve("data"));

        PlatformConfig pg = PlatformConfig.load(config("""
                storage:
                  type: postgres
                  url: jdbc:postgresql://db:5432/mcpgp
                  user: mcpgp
                  password: "${file:/run/secrets/db_password}"
                """), Map.of("MCPGP_PLATFORM_STORAGE__SCHEMA", "platform"));
        assertThat(pg.postgres()).isEqualTo(new PlatformConfig.Postgres("jdbc:postgresql://db:5432/mcpgp", "mcpgp",
                "${file:/run/secrets/db_password}", "platform", 10));

        // the environment alone is enough (docker compose)
        PlatformConfig env = PlatformConfig.load(null, Map.of("MCPGP_PLATFORM_STORAGE__TYPE", "postgres",
                "MCPGP_PLATFORM_STORAGE__URL", "jdbc:postgresql://db/mcpgp", "MCPGP_PLATFORM_STORAGE__PASSWORD", "${env:DB}"));
        assertThat(env.postgres().schema()).isEqualTo("mcpgp");

        assertThatThrownBy(() -> PlatformConfig.load(config("storage: { type: postgres }\n"), Map.of())).hasMessageContaining("storage.url");
        assertThatThrownBy(() -> PlatformConfig.load(config("storage: { type: postgres, url: 'jdbc:postgresql://db/x', password: hunter2 }\n"),
                Map.of())).hasMessageContaining("reference");
        assertThatThrownBy(() -> PlatformConfig.load(config("storage: { type: s3 }\n"), Map.of())).hasMessageContaining("files or postgres");
    }

    @Test
    void theAdministratorComesFromTheConfigOnce() throws Exception {
        Path password = dir.resolve("admin_password");
        Files.writeString(password, "a long first password\n");
        PlatformConfig config = PlatformConfig.load(config("admin: { name: ana, password: \"${file:" + password + "}\" }\n"),
                Map.of("MCPGP_PLATFORM_STORAGE__ROOT", dir.resolve("store").toString()));
        assertThat(config.admin()).isEqualTo(new PlatformConfig.Admin("ana", "${file:" + password + "}"));
        assertThatThrownBy(() -> PlatformConfig.load(config("admin: { name: ana, password: plain }\n"), Map.of())).hasMessageContaining("reference");

        Store store = new FileStore(config.storageRoot());
        assertThat(Deployment.warnings(config, store)).anyMatch(w -> w.contains("no administrator"));
        Deployment.bootstrap(config, store);
        Deployment.bootstrap(config, store);    // a later start leaves it alone
        assertThat(store.identities()).singleElement().satisfies(i -> {
            assertThat(i.name()).isEqualTo("ana");
            assertThat(i.kind()).isEqualTo("person");
        });
        assertThat(store.credentials()).hasSize(1);
        assertThat(Deployment.warnings(config, store)).noneMatch(w -> w.contains("no administrator"));
    }

    @Test
    void warnsAboutWhatDoesNotBelongOnASharedInstallation() {
        Store store = new FileStore(dir.resolve("store"));
        PlatformConfig open = new PlatformConfig(dir.resolve("store"), "0.0.0.0", 8443, null, PlatformConfig.Retention.DEFAULT, "acme", true,
                PlatformConfig.Guardrails.DEFAULT, null, null, "http://mcpgp.acme.internal");
        List<String> warnings = Deployment.warnings(open, store);
        assertThat(warnings).anyMatch(w -> w.contains("unencrypted")).anyMatch(w -> w.contains("unsigned"));

        PlatformConfig noUrl = new PlatformConfig(dir.resolve("store"), "0.0.0.0", 8443, null, PlatformConfig.Retention.DEFAULT, "acme", true,
                PlatformConfig.Guardrails.DEFAULT, null, null, null);
        assertThat(Deployment.warnings(noUrl, store)).anyMatch(w -> w.contains("publicUrl is not set"));

        PlatformConfig laptop = new PlatformConfig(dir.resolve("store"), "127.0.0.1", 8443);
        assertThat(Deployment.warnings(laptop, store)).noneMatch(w -> w.contains("publicUrl"));
    }

    @Test
    void healthAndSecureCookiesBehindATlsProxy() throws Exception {
        Path password = dir.resolve("admin_password");
        Files.writeString(password, "a long first password");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        PlatformConfig config = TestStores.of(new PlatformConfig(dir.resolve("store"), "127.0.0.1", port, null, PlatformConfig.Retention.DEFAULT,
                "acme", true, PlatformConfig.Guardrails.DEFAULT, null, null, "https://mcpgp.acme.internal"))
                .withAdmin(new PlatformConfig.Admin("ana", "${file:" + password + "}"));
        try (ConfigurableApplicationContext context = PlatformServer.start(config)) {
            String root = "http://127.0.0.1:" + port;
            HttpResponse<String> health = HTTP.send(HttpRequest.newBuilder(URI.create(root + "/healthz")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(health.body()).path("status").asText()).isEqualTo("up");

            HttpResponse<String> session = HTTP.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/sessions"))
                    .header("Content-Type", "application/json").header("X-Mcpgp-Client", "web")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"ana\",\"password\":\"a long first password\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(session.statusCode()).isEqualTo(201);
            assertThat(session.headers().firstValue("Set-Cookie").orElseThrow()).contains("Secure").contains("HttpOnly");
        }
    }

    @Test
    void copyStoreMovesEverythingIntoAnEmptyStore() throws Exception {
        Path source = dir.resolve("old");
        Store old = new FileStore(source);
        PlatformConfig sourceConfig = new PlatformConfig(source, "127.0.0.1", 0);
        ByteArrayOutputStream init = new ByteArrayOutputStream();
        assertThat(PlatformServer.init(sourceConfig, new String[] {"init", "--admin", "ana"}, new PrintStream(init, true, StandardCharsets.UTF_8))).isZero();
        String blob = old.putBlob("openapi: 3.1.0\n".getBytes(StandardCharsets.UTF_8));
        Model.Version version = new Model.Version(FileStore.sha256(blob.getBytes(StandardCharsets.UTF_8)), "mcpgp.yaml", Map.of("mcpgp.yaml", blob));
        old.saveVersion(version);
        old.saveServer(new Model.Server("orders-mcp", "Orders", null, false, "t", "t",
                List.of(new Model.VersionRef(1, version.digest(), "first", "ana", "t")), 0), -1);
        old.saveBuild(new Model.Build("b-abc123", "orders-mcp", 1, version.digest(), "succeeded", "ana", "person", "t", "t", "t", 1, "1.0.0+1",
                false, null, false, null, false, List.of(new Model.Artifact("orders.zip", 3, "x")), null));
        old.writeBuildFile("b-abc123", "orders.zip", new byte[] {1, 2, 3});
        old.appendBuildLog("b-abc123", "built\n");

        PlatformConfig target = TestStores.of(new PlatformConfig(dir.resolve("new"), "127.0.0.1", 0));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(PlatformServer.copyStore(target, new String[] {"copy-store", "--from", source.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8))).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("1 servers").contains("1 builds (1 logs)").contains("1 identities");

        Store copy = PlatformServer.openStore(target);
        try {
            assertThat(copy.server("orders-mcp")).isPresent();
            assertThat(copy.buildFile("b-abc123", "orders.zip")).hasValueSatisfying(b -> assertThat(b).containsExactly(1, 2, 3));
            assertThat(copy.buildFile("b-abc123", "log.txt")).hasValueSatisfying(b -> assertThat(new String(b, StandardCharsets.UTF_8)).isEqualTo("built\n"));
            assertThat(copy.credentials()).isEmpty();      // init without a password: there was none to copy
            assertThat(copy.activity(a -> true, 10)).extracting(Model.Activity::action).containsExactly("platform.copy-store", "platform.init");
        } finally {
            if (copy instanceof AutoCloseable c) {
                c.close();
            }
        }

        // a second copy is refused: the target isn't empty any more
        ByteArrayOutputStream again = new ByteArrayOutputStream();
        assertThat(PlatformServer.copyStore(target, new String[] {"copy-store", "--from", source.toString()},
                new PrintStream(again, true, StandardCharsets.UTF_8))).isEqualTo(1);
        assertThat(again.toString(StandardCharsets.UTF_8)).contains("not empty");
    }

    @Test
    void servesHttpsItselfWithACertificateAndRefusesSignInOverPlainHttp() throws Exception {
        Path keystore = dir.resolve("tls.p12");
        TlsFiles.selfSigned("localhost", keystore, "keystore password", dir.resolve("certificate.pem"));
        Files.writeString(dir.resolve("tls_password"), "keystore password\n");
        Path password = dir.resolve("admin_password");
        Files.writeString(password, "a long first password");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        PlatformConfig config = new PlatformConfig(dir.resolve("store"), "127.0.0.1", port, null, PlatformConfig.Retention.DEFAULT, "acme", true,
                PlatformConfig.Guardrails.DEFAULT, null, null, "https://localhost:" + port)
                .withAdmin(new PlatformConfig.Admin("ana", "${file:" + password + "}"))
                .withTls(new PlatformConfig.Tls(null, null, keystore.toString(), "${file:" + dir.resolve("tls_password") + "}"));
        // trust exactly this certificate, as a client given certificate.pem would
        java.security.KeyStore trust = java.security.KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        try (var in = Files.newInputStream(dir.resolve("certificate.pem"))) {
            trust.setCertificateEntry("mcpgp", java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        HttpClient https = HttpClient.newBuilder().sslContext(ssl).build();

        try (ConfigurableApplicationContext context = PlatformServer.start(config)) {
            String root = "https://localhost:" + port;
            assertThat(https.send(HttpRequest.newBuilder(URI.create(root + "/healthz")).build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(200);
            String body = "{\"name\":\"ana\",\"password\":\"a long first password\"}";
            HttpResponse<String> session = https.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/sessions"))
                    .header("Content-Type", "application/json").header("X-Mcpgp-Client", "web").header("Origin", root)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(session.statusCode()).as(session.body()).isEqualTo(201);
            assertThat(session.headers().firstValue("Set-Cookie").orElseThrow()).contains("Secure");

            // a page on plain HTTP (another address of the same host): told where to go instead of a cookie it can't keep
            HttpResponse<String> wrong = https.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/sessions"))
                    .header("Content-Type", "application/json").header("X-Mcpgp-Client", "web").header("Origin", "http://localhost:8443")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(wrong.statusCode()).isEqualTo(409);
            assertThat(JSON.readTree(wrong.body()).path("detail").asText()).contains("Open https://localhost:" + port + " instead");
            assertThat(JSON.readTree(https.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/platform")).build(),
                    HttpResponse.BodyHandlers.ofString()).body()).path("publicUrl").asText()).isEqualTo(root);
        }
    }

    @Test
    void tlsSettingsAreChecked() throws Exception {
        assertThatThrownBy(() -> PlatformConfig.load(config("http: { tls: { certificate: /etc/c.pem } }\n"), Map.of())).hasMessageContaining("privateKey");
        assertThatThrownBy(() -> PlatformConfig.load(config("http: { tls: { keystore: /x.p12, password: secret } }\n"), Map.of())).hasMessageContaining("reference");
        PlatformConfig env = PlatformConfig.load(null, Map.of("MCPGP_PLATFORM_HTTP__TLS__KEYSTORE", "/run/secrets/tls_keystore",
                "MCPGP_PLATFORM_HTTP__TLS__PASSWORD", "${file:/run/secrets/tls_keystore_password}"));
        assertThat(env.tls().pem()).isFalse();
        assertThat(PlatformServer.tlsProperties(new PlatformConfig(dir, "127.0.0.1", 1))).isEmpty();     // no tls: plain HTTP, as before
    }
}
