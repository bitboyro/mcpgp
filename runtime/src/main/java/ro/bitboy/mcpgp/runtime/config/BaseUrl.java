package ro.bitboy.mcpgp.runtime.config;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Target server addresses (runtime-config §4.1): no scheme → https; no port → 443 / 80; no path → the
 * contract's base path is appended; a path → used as written.
 */
public final class BaseUrl {

    private BaseUrl() {
    }

    /** True when the address carries its own path (then no base path is appended). */
    public static boolean hasPath(String address) {
        return !parse(address).getRawPath().isEmpty() && !parse(address).getRawPath().equals("/");
    }

    /** The full base URI for a contract, without a trailing slash. */
    public static URI resolve(String address, String contractBasePath) {
        URI uri = parse(address);
        String path = uri.getRawPath();
        if (path.isEmpty() || path.equals("/")) {
            path = contractBasePath == null ? "" : contractBasePath;
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), port(uri), null, null, null)
                    .resolve(path.isEmpty() ? "" : (path.startsWith("/") ? path : "/" + path));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("not a valid address: " + address);
        }
    }

    /** Parses and checks an address; throws {@link IllegalArgumentException} with a readable reason. */
    public static URI parse(String address) {
        String text = address.strip();
        if (!text.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            text = "https://" + text;
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("not a valid address: " + address);
        }
        String scheme = uri.getScheme().toLowerCase();
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalArgumentException("only http and https addresses are supported: " + address);
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("no host in address: " + address);
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("an address may not contain credentials, a query or a fragment: " + address);
        }
        return uri;
    }

    private static int port(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return uri.getScheme().equalsIgnoreCase("http") ? 80 : 443;
    }
}
