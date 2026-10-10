package ro.bitboy.mcpgp.runtime.config;

import com.fasterxml.jackson.databind.JsonNode;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds one instance's TLS context (runtime-config §5): its own in-memory truststore (never the
 * global one), optional client certificate for mTLS, and the hostname-only relaxation. There is no
 * trust-all mode: the certificate chain is always validated.
 */
public final class TlsBuilder {

    /**
     * @param context      the SSL context for this instance's connections
     * @param skipHostname {@code insecureSkipHostnameVerify}: chain validated, hostname not checked
     * @param files        SHA-256 of every {@code path} file read, to detect rotation (§5.4)
     */
    public record InstanceTls(SSLContext context, boolean skipHostname, Map<Path, String> files) {

        /** True when a material file changed since it was loaded. */
        public boolean filesChanged() {
            for (Map.Entry<Path, String> file : files.entrySet()) {
                try {
                    if (!sha256(Files.readAllBytes(file.getKey())).equals(file.getValue())) {
                        return true;
                    }
                } catch (IOException e) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final Pattern PEM = Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");
    private static final char[] NO_PASSWORD = new char[0];

    private TlsBuilder() {
    }

    /**
     * @param tls     the instance's {@code tls} object (secrets resolved)
     * @param baseDir where relative {@code path} values start
     * @param where   the config location, for messages ({@code /instances/0/tls})
     * @throws IllegalArgumentException naming the field (never the content) when material is unusable
     */
    public static InstanceTls build(JsonNode tls, Path baseDir, String where) {
        Map<Path, String> files = new LinkedHashMap<>();
        try {
            // trust
            List<X509Certificate> cas = tls.has("ca") ? certificates(material(tls.get("ca"), baseDir, where + "/ca", files), where + "/ca") : List.of();
            String trust = tls.path("trust").asText(cas.isEmpty() ? "system" : "ca-only");
            if (!trust.equals("system") && cas.isEmpty()) {
                throw new IllegalArgumentException(where + "/trust: '" + trust + "' needs tls.ca");
            }
            List<X509TrustManager> managers = new ArrayList<>();
            if (!trust.equals("ca-only")) {
                managers.add(trustManager(null));
            }
            if (!trust.equals("system")) {
                KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
                store.load(null, null);
                for (int i = 0; i < cas.size(); i++) {
                    store.setCertificateEntry("ca-" + i, cas.get(i));
                }
                managers.add(trustManager(store));
            }
            boolean skipHostname = tls.path("insecureSkipHostnameVerify").asBoolean(false);
            TrustManager trustManager = new Trust(managers, skipHostname);

            // client certificate (mTLS)
            KeyManager[] keyManagers = null;
            if (tls.has("clientCert")) {
                KeyStore keys = clientKeys(tls, baseDir, where, files);
                char[] password = tls.has("password") ? tls.get("password").asText().toCharArray() : NO_PASSWORD;
                KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                factory.init(keys, password);
                keyManagers = factory.getKeyManagers();
            } else if (tls.has("clientKey")) {
                throw new IllegalArgumentException(where + "/clientKey: needs tls.clientCert");
            }

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers, new TrustManager[] {trustManager}, null);
            return new InstanceTls(context, skipHostname, Map.copyOf(files));
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException(where + ": TLS settings can't be used (" + e.getClass().getSimpleName() + ")");
        }
    }

    // ---------------------------------------------------------------------- material

    /** The bytes of {@code {path}} or {@code {base64}}; base64 may hold PEM text or DER / PKCS#12 bytes. */
    private static byte[] material(JsonNode node, Path baseDir, String where, Map<Path, String> files) {
        if (node.has("path") == node.has("base64")) {
            throw new IllegalArgumentException(where + ": give exactly one of path or base64");
        }
        if (node.has("path")) {
            Path file = baseDir.resolve(node.get("path").asText()).normalize();
            try {
                byte[] bytes = Files.readAllBytes(file);
                files.put(file, sha256(bytes));
                return bytes;
            } catch (IOException e) {
                throw new IllegalArgumentException(where + "/path: cannot read " + node.get("path").asText());
            }
        }
        try {
            return Base64.getMimeDecoder().decode(node.get("base64").asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + "/base64: not valid base64");
        }
    }

    private static List<X509Certificate> certificates(byte[] bytes, String where) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> result = new ArrayList<>();
            for (Certificate c : factory.generateCertificates(new ByteArrayInputStream(bytes))) {
                result.add((X509Certificate) c);
            }
            if (result.isEmpty()) {
                throw new IllegalArgumentException(where + ": no certificate found");
            }
            return result;
        } catch (CertificateException e) {
            throw new IllegalArgumentException(where + ": not a PEM or DER certificate (bundle)");
        }
    }

    private static KeyStore clientKeys(JsonNode tls, Path baseDir, String where, Map<Path, String> files)
            throws GeneralSecurityException, IOException {
        byte[] cert = material(tls.get("clientCert"), baseDir, where + "/clientCert", files);
        char[] password = tls.has("password") ? tls.get("password").asText().toCharArray() : NO_PASSWORD;
        if (!isPem(cert)) {
            // PKCS#12
            KeyStore store = KeyStore.getInstance("PKCS12");
            try {
                store.load(new ByteArrayInputStream(cert), password);
            } catch (IOException e) {
                throw new IllegalArgumentException(where + "/clientCert: not a PKCS#12 file, or the password is wrong");
            }
            return store;
        }
        if (!tls.has("clientKey")) {
            throw new IllegalArgumentException(where + "/clientKey: a PEM client certificate needs clientKey");
        }
        List<X509Certificate> chain = certificates(cert, where + "/clientCert");
        PrivateKey key = privateKey(material(tls.get("clientKey"), baseDir, where + "/clientKey", files), where + "/clientKey");
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("client", key, password, chain.toArray(new Certificate[0]));
        return store;
    }

    private static PrivateKey privateKey(byte[] bytes, String where) {
        Matcher pem = PEM.matcher(new String(bytes, StandardCharsets.US_ASCII));
        if (!pem.find()) {
            throw new IllegalArgumentException(where + ": expected a PEM private key");
        }
        if (!pem.group(1).equals("PRIVATE KEY")) {
            throw new IllegalArgumentException(where + ": " + pem.group(1) + " is not supported; convert it to PKCS#8 "
                    + "(openssl pkcs8 -topk8 -nocrypt)");
        }
        byte[] der = Base64.getMimeDecoder().decode(pem.group(2));
        for (String algorithm : List.of("RSA", "EC", "Ed25519")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (GeneralSecurityException e) {
                // try the next algorithm
            }
        }
        throw new IllegalArgumentException(where + ": the private key can't be read (RSA, EC and Ed25519 are supported)");
    }

    private static boolean isPem(byte[] bytes) {
        return new String(bytes, 0, Math.min(bytes.length, 64), StandardCharsets.US_ASCII).contains("-----BEGIN");
    }

    private static X509TrustManager trustManager(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new GeneralSecurityException("no X509 trust manager");
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Trusts a chain when any of the managers does. With {@code skipHostname} the delegate's checks
     * without an engine or socket are used: the chain is fully validated, only the hostname is not.
     */
    private static final class Trust extends X509ExtendedTrustManager {
        private final List<X509TrustManager> managers;
        private final boolean skipHostname;

        Trust(List<X509TrustManager> managers, boolean skipHostname) {
            this.managers = managers;
            this.skipHostname = skipHostname;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            check(chain, authType, m -> {
                if (!skipHostname && m instanceof X509ExtendedTrustManager extended) {
                    extended.checkServerTrusted(chain, authType, engine);
                } else {
                    m.checkServerTrusted(chain, authType);
                }
            });
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            check(chain, authType, m -> {
                if (!skipHostname && m instanceof X509ExtendedTrustManager extended) {
                    extended.checkServerTrusted(chain, authType, socket);
                } else {
                    m.checkServerTrusted(chain, authType);
                }
            });
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, authType, m -> m.checkServerTrusted(chain, authType));
        }

        private interface Check {
            void run(X509TrustManager manager) throws CertificateException;
        }

        private void check(X509Certificate[] chain, String authType, Check check) throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager manager : managers) {
                try {
                    check.run(manager);
                    return;
                } catch (CertificateException e) {
                    last = e;
                }
            }
            throw last != null ? last : new CertificateException("no trust anchors");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            throw new CertificateException("client side only");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            throw new CertificateException("client side only");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client side only");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> issuers = new ArrayList<>();
            managers.forEach(m -> issuers.addAll(List.of(m.getAcceptedIssuers())));
            return issuers.toArray(new X509Certificate[0]);
        }
    }
}
