package ro.bitboy.mcpgp.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.List;

/**
 * Test certificates, generated once per test run with the JDK's keytool (nothing is committed):
 * a server certificate for 127.0.0.1/localhost, one for another host name, and a client certificate
 * as PKCS#12 and as PEM certificate + PKCS#8 key. All self-signed.
 */
public final class TestCerts {

    public static final String PASSWORD = "changeit-test";
    private static TestCerts instance;

    public final Path dir;
    /** Keystores for the test HTTPS server. */
    public final Path serverP12;
    public final Path otherHostP12;
    /** PEM certificates clients trust. */
    public final Path serverPem;
    public final Path otherHostPem;
    /** The client certificate (mTLS), as PKCS#12 and as PEM certificate + PKCS#8 key. */
    public final Path clientP12;
    public final Path clientPem;
    public final Path clientKeyPem;

    private TestCerts(Path dir) throws IOException, InterruptedException {
        this.dir = dir;
        serverP12 = keypair("server", "CN=localhost", "SAN=ip:127.0.0.1,dns:localhost");
        otherHostP12 = keypair("other", "CN=other.example", "SAN=dns:other.example");
        clientP12 = keypair("client", "CN=mcpgp-test-client", null);
        serverPem = exportPem("server", serverP12);
        otherHostPem = exportPem("other", otherHostP12);
        clientPem = exportPem("client", clientP12);
        clientKeyPem = exportKey(clientP12, dir.resolve("client.key"));
    }

    public static synchronized TestCerts get() {
        if (instance == null) {
            try {
                instance = new TestCerts(Files.createTempDirectory("mcpgp-certs"));
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException("cannot generate test certificates", e);
            }
        }
        return instance;
    }

    public static String base64(Path file) {
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path keypair(String alias, String dname, String san) throws IOException, InterruptedException {
        Path store = dir.resolve(alias + ".p12");
        List<String> command = new java.util.ArrayList<>(List.of(keytool(), "-genkeypair", "-alias", alias, "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "30", "-dname", dname, "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD));
        if (san != null) {
            command.addAll(List.of("-ext", san));
        }
        run(command);
        return store;
    }

    private Path exportPem(String alias, Path store) throws IOException, InterruptedException {
        Path pem = dir.resolve(alias + ".pem");
        run(List.of(keytool(), "-exportcert", "-rfc", "-alias", alias, "-keystore", store.toString(), "-storetype", "PKCS12",
                "-storepass", PASSWORD, "-file", pem.toString()));
        return pem;
    }

    private static Path exportKey(Path store, Path target) {
        try (InputStream in = Files.newInputStream(store)) {
            KeyStore keys = KeyStore.getInstance("PKCS12");
            keys.load(in, PASSWORD.toCharArray());
            Key key = keys.getKey("client", PASSWORD.toCharArray());
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            Files.writeString(target, pem);
            return target;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The PEM certificate of a keystore entry, as loaded. */
    public static Certificate certificate(Path store, String alias) {
        try (InputStream in = Files.newInputStream(store)) {
            KeyStore keys = KeyStore.getInstance("PKCS12");
            keys.load(in, PASSWORD.toCharArray());
            return keys.getCertificate(alias);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("keytool failed: " + output);
        }
    }

    private static String keytool() {
        return Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    }
}
