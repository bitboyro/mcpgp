package ro.bitboy.mcpgp.runtime.diag;

import ro.bitboy.mcpgp.runtime.config.BaseUrl;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.mcp.ToolError;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Checks an instance from the server's side: the certificate chain its host presents, and whether
 * DNS, TCP and TLS (with the instance's own trust) work. Used by the diagnostics pack and by the
 * {@code doctor} and {@code instance} commands.
 */
public final class Probe {

    private static final int CONNECT_TIMEOUT_MS = 5000;

    private Probe() {
    }

    /** The PEM text of a certificate. */
    public static String pem(X509Certificate cert) {
        try {
            return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (GeneralSecurityException e) {
            return "";
        }
    }

    /**
     * The chain the instance's host presents (runtime-config §5.5). The handshake only reads the
     * certificates: nothing is sent and the connection is closed. Limited to configured instances.
     */
    public static Map<String, Object> certificate(RuntimeConfig.Instance instance) {
        URI uri = BaseUrl.resolve(instance.baseUrl(), "");
        if (!uri.getScheme().equals("https")) {
            throw new ToolError("INPUT_VALIDATION", instance.id() + " uses plain HTTP; there is no certificate");
        }
        List<X509Certificate> chain = new ArrayList<>();
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new Capture(chain)}, null);
            try (Socket raw = new Socket()) {
                raw.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), CONNECT_TIMEOUT_MS);
                try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(raw, uri.getHost(), uri.getPort(), true)) {
                    SSLParameters parameters = socket.getSSLParameters();
                    if (!Character.isDigit(uri.getHost().charAt(0))) {
                        parameters.setServerNames(List.of(new SNIHostName(uri.getHost())));
                    }
                    socket.setSSLParameters(parameters);
                    socket.setSoTimeout(CONNECT_TIMEOUT_MS);
                    socket.startHandshake();
                }
            }
        } catch (IOException | GeneralSecurityException e) {
            if (chain.isEmpty()) {
                throw new ToolError("UPSTREAM_UNREACHABLE", "Could not read the certificate of " + instance.id() + ": "
                        + e.getClass().getSimpleName());
            }
        }
        List<Map<String, Object>> certificates = new ArrayList<>();
        for (X509Certificate cert : chain) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("subject", cert.getSubjectX500Principal().getName());
            line.put("issuer", cert.getIssuerX500Principal().getName());
            line.put("notBefore", cert.getNotBefore().toInstant().toString());
            line.put("notAfter", cert.getNotAfter().toInstant().toString());
            line.put("sha256", fingerprint(cert));
            line.put("pem", pem(cert));
            certificates.add(line);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instance", instance.id());
        result.put("host", uri.getHost());
        result.put("chain", certificates);
        boolean covers = !chain.isEmpty() && covers(chain.get(0), uri.getHost());
        result.put("hostnameMatches", covers);
        if (!covers) {
            result.put("warning", "The certificate does not cover " + uri.getHost() + "; check the address before trusting it");
        }
        return result;
    }

    public static Map<String, Object> connectivity(RuntimeConfig.Instance instance) {
        URI uri = BaseUrl.resolve(instance.baseUrl(), "");
        Map<String, Object> steps = new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instance", instance.id());
        result.put("address", uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort());
        result.put("steps", steps);
        try {
            steps.put("dns", InetAddress.getByName(uri.getHost()).getHostAddress());
        } catch (IOException e) {
            steps.put("dns", "failed: the host name does not resolve");
            result.put("ok", false);
            return result;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), CONNECT_TIMEOUT_MS);
            steps.put("tcp", "connected");
        } catch (IOException e) {
            steps.put("tcp", "failed: " + e.getClass().getSimpleName());
            result.put("ok", false);
            return result;
        }
        if (uri.getScheme().equals("https")) {
            try {
                SSLContext context = instance.tlsContext() != null ? instance.tlsContext().context() : SSLContext.getDefault();
                try (Socket raw = new Socket()) {
                    raw.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), CONNECT_TIMEOUT_MS);
                    try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(raw, uri.getHost(), uri.getPort(), true)) {
                        SSLParameters parameters = socket.getSSLParameters();
                        if (instance.tlsContext() == null || !instance.tlsContext().skipHostname()) {
                            parameters.setEndpointIdentificationAlgorithm("HTTPS");
                        }
                        socket.setSSLParameters(parameters);
                        socket.setSoTimeout(CONNECT_TIMEOUT_MS);
                        socket.startHandshake();
                    }
                }
                steps.put("tls", "trusted");
            } catch (IOException | GeneralSecurityException e) {
                steps.put("tls", "failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                result.put("ok", false);
                result.put("hint", "See the certificate the host presents with `instance trust <id>`, or with the tls_certificate_fetch tool when the diagnostics pack is enabled");
                return result;
            }
        }
        result.put("ok", true);
        return result;
    }

    private static boolean covers(X509Certificate cert, String host) {
        try {
            Collection<List<?>> names = cert.getSubjectAlternativeNames();
            if (names != null) {
                for (List<?> name : names) {
                    String value = String.valueOf(name.get(1)).toLowerCase(Locale.ROOT);
                    String h = host.toLowerCase(Locale.ROOT);
                    if (value.equals(h)) {
                        return true;
                    }
                    // a wildcard covers exactly one label: *.example.com covers a.example.com, not a.b.example.com
                    if (value.startsWith("*.") && h.endsWith(value.substring(1))) {
                        String label = h.substring(0, h.length() - value.length() + 1);
                        if (!label.isEmpty() && !label.contains(".")) {
                            return true;
                        }
                    }
                }
            }
        } catch (CertificateParsingException e) {
            return false;
        }
        return false;
    }

    private static String fingerprint(X509Certificate cert) {
        try {
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (GeneralSecurityException e) {
            return "";
        }
    }

    /** Records the presented chain; used only to read certificates, never for a connection that carries data. */
    private record Capture(List<X509Certificate> chain) implements X509TrustManager {
        @Override
        public void checkServerTrusted(X509Certificate[] certificates, String authType) {
            chain.addAll(List.of(certificates));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] certificates, String authType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
