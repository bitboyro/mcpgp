package ro.bitboy.mcpgp.platform;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The platform's own HTTPS (docs/guides/self-hosting.md): a self-signed certificate for {@code setup},
 * made with the JRE's {@code keytool}, and what {@code check} reads from a certificate.
 */
final class TlsFiles {

    private TlsFiles() {
    }

    /** The host of an address: {@code https://mcpgp.example.com:9443} → {@code mcpgp.example.com}. */
    static String host(String publicUrl) {
        return URI.create(publicUrl).getHost();
    }

    /** The port of an address, 443 or 80 when it names none. */
    static int port(String publicUrl) {
        URI uri = URI.create(publicUrl);
        return uri.getPort() > 0 ? uri.getPort() : uri.getScheme().equals("https") ? 443 : 80;
    }

    /**
     * A self-signed certificate for {@code host}, valid for a year, in a PKCS12 keystore, and the
     * certificate alone as PEM (for browsers and clients to trust).
     */
    static void selfSigned(String host, Path keystore, String password, Path certificatePem) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        String san = host.matches("[0-9.]+|.*:.*") ? "ip:" + host : "dns:" + host;
        run(List.of(java, "-genkeypair", "-alias", "mcpgp", "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-dname", "CN=" + host, "-ext", "SAN=" + san, "-validity", "365", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", password, "-keypass", password));
        run(List.of(java, "-exportcert", "-rfc", "-alias", "mcpgp", "-keystore", keystore.toString(), "-storepass", password,
                "-file", certificatePem.toString()));
    }

    private static void run(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("keytool failed: " + output.strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    /** A certificate and whether its key is there and belongs to it. */
    record Loaded(X509Certificate certificate, boolean keyMatches) {
    }

    static Loaded load(PlatformConfig.Tls tls, String password) throws Exception {
        if (!tls.pem()) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(Path.of(tls.keystore()))) {
                store.load(in, password.toCharArray());
            }
            for (String alias : java.util.Collections.list(store.aliases())) {
                if (store.isKeyEntry(alias)) {
                    PrivateKey key = (PrivateKey) store.getKey(alias, password.toCharArray());
                    X509Certificate cert = (X509Certificate) store.getCertificate(alias);
                    return new Loaded(cert, matches(key, cert));
                }
            }
            throw new IllegalArgumentException("the keystore has no private key");
        }
        X509Certificate cert;
        try (InputStream in = Files.newInputStream(Path.of(tls.certificate()))) {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        return new Loaded(cert, matches(privateKey(Files.readString(Path.of(tls.privateKey()))), cert));
    }

    /** A PKCS#8 PEM key ({@code BEGIN PRIVATE KEY}), RSA or EC. */
    static PrivateKey privateKey(String pem) throws Exception {
        if (!pem.contains("BEGIN PRIVATE KEY")) {
            throw new IllegalArgumentException("the key isn't a PKCS#8 PEM file (BEGIN PRIVATE KEY); convert it with: "
                    + "openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem");
        }
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        for (String algorithm : List.of("RSA", "EC")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (java.security.spec.InvalidKeySpecException e) {
                // the other one
            }
        }
        throw new IllegalArgumentException("the key is neither RSA nor EC");
    }

    private static boolean matches(PrivateKey key, X509Certificate cert) throws Exception {
        String algorithm = key.getAlgorithm().equals("EC") ? "SHA256withECDSA" : "SHA256withRSA";
        byte[] data = "mcpgp check".getBytes(StandardCharsets.UTF_8);
        Signature sign = Signature.getInstance(algorithm);
        sign.initSign(key);
        sign.update(data);
        byte[] signature = sign.sign();
        Signature verify = Signature.getInstance(algorithm);
        verify.initVerify(cert.getPublicKey());
        verify.update(data);
        return verify.verify(signature);
    }

    /** The names the certificate is for: its DNS and IP names, else its CN. */
    static List<String> names(X509Certificate cert) throws Exception {
        List<String> names = new ArrayList<>();
        Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        if (sans != null) {
            for (List<?> san : sans) {
                int type = (Integer) san.get(0);
                if (type == 2 || type == 7) {
                    names.add(String.valueOf(san.get(1)));
                }
            }
        }
        if (names.isEmpty()) {
            String dn = cert.getSubjectX500Principal().getName();
            for (String part : dn.split(",")) {
                if (part.strip().startsWith("CN=")) {
                    names.add(part.strip().substring(3));
                }
            }
        }
        return names;
    }

    static boolean covers(List<String> names, String host) {
        String h = host.toLowerCase();
        for (String name : names) {
            String n = name.toLowerCase();
            if (n.equals(h) || (n.startsWith("*.") && h.endsWith(n.substring(1)) && h.indexOf('.') == h.length() - n.length() + 1)) {
                return true;
            }
        }
        return false;
    }

    static boolean selfSigned(X509Certificate cert) {
        return cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());
    }

    static Duration remaining(X509Certificate cert, Instant now) {
        return Duration.between(now, cert.getNotAfter().toInstant());
    }
}
