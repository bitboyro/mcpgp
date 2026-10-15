package ro.bitboy.mcpgp.platform.service;

import ro.bitboy.mcpgp.platform.PlatformConfig;
import ro.bitboy.mcpgp.platform.Secrets;
import ro.bitboy.mcpgp.platform.api.ApiException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Fetches an API's spec from where it is published (platform-api.md §2.1): only from a host an
 * administrator lists in {@code specHosts}, over HTTPS (plain HTTP only for a host listed with
 * {@code http://}), following a redirect only to another listed host, within the size limit of an
 * uploaded file. A host's credential is sent as its configured header, read from its reference at
 * each fetch; it is never stored or shown. The platform fetches nothing else: no target servers.
 */
public final class SpecFetch {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int REDIRECTS = 3;

    /** What was fetched: the body, the file name it gets in the project, and the address it came from. */
    public record Fetched(String url, String name, byte[] body) {
    }

    private final List<PlatformConfig.SpecHost> hosts;
    private final Map<String, String> env;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public SpecFetch(List<PlatformConfig.SpecHost> hosts, Map<String, String> env) {
        this.hosts = hosts == null ? List.of() : hosts;
        this.env = env;
    }

    public Fetched fetch(String url) {
        URI uri = parse(url);
        for (int hop = 0; hop <= REDIRECTS; hop++) {
            PlatformConfig.SpecHost host = allowed(uri);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                    .header("Accept", "application/json, application/yaml, text/yaml, */*;q=0.5").GET();
            if (host.header() != null) {
                String value = Secrets.resolve(host.value(), env).orElseThrow(() -> new ApiException(500, "SPEC_FETCH_FAILED",
                        "the credential for " + host.host() + " (" + host.value() + ") isn't set on the platform; ask an administrator"));
                request.header(host.header(), value);
            }
            HttpResponse<InputStream> response;
            try {
                response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new ApiException(502, "SPEC_FETCH_FAILED", uri.getHost() + " didn't answer: " + e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(502, "SPEC_FETCH_FAILED", "the fetch was interrupted");
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                close(response);
                Optional<String> location = response.headers().firstValue("Location");
                if (location.isEmpty()) {
                    throw new ApiException(502, "SPEC_FETCH_FAILED", uri + " answered HTTP " + status + " without a Location");
                }
                uri = parse(uri.resolve(location.get()).toString());
                continue;
            }
            if (status != 200) {
                close(response);
                throw new ApiException(502, "SPEC_FETCH_FAILED", uri + " answered HTTP " + status
                        + (status == 401 || status == 403 ? "; if it needs a credential, an administrator sets one for " + host.host() + " in specHosts" : ""));
            }
            byte[] body;
            try (InputStream in = response.body()) {
                body = in.readNBytes(Projects.MAX_FILE + 1);
            } catch (IOException e) {
                throw new ApiException(502, "SPEC_FETCH_FAILED", "reading " + uri + " failed: " + e.getClass().getSimpleName());
            }
            if (body.length > Projects.MAX_FILE) {
                throw ApiException.validation(uri + " is larger than " + Projects.MAX_FILE / 1024 / 1024 + " MB");
            }
            return new Fetched(uri.toString(), name(uri, response.headers().firstValue("Content-Type").orElse(""), body), body);
        }
        throw new ApiException(502, "SPEC_FETCH_FAILED", url + " redirects more than " + REDIRECTS + " times");
    }

    private PlatformConfig.SpecHost allowed(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        for (PlatformConfig.SpecHost entry : hosts) {
            String[] parts = entry.host().split(":");
            int listed = parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
            boolean schemeOk = scheme.equals("https") || entry.plainHttp() && scheme.equals("http");
            int effective = port == -1 ? (scheme.equals("https") ? 443 : 80) : port;
            int expected = listed == -1 ? (scheme.equals("https") ? 443 : 80) : listed;
            if (parts[0].equals(host) && schemeOk && effective == expected) {
                return entry;
            }
        }
        boolean named = hosts.stream().anyMatch(h -> h.host().split(":")[0].equals(host));
        throw new ApiException(422, "SPEC_HOST_NOT_ALLOWED", named
                ? uri.getScheme() + "://" + uri.getRawAuthority() + " isn't allowed: " + host + " is listed in specHosts with another scheme or port"
                : host + " isn't in the platform's specHosts; an administrator can allow it (platform.yaml), or upload the spec instead");
    }

    private static URI parse(String url) {
        URI uri;
        try {
            uri = new URI(url == null ? "" : url.strip());
        } catch (URISyntaxException e) {
            throw ApiException.validation("url: not a valid address");
        }
        if (uri.getScheme() == null || uri.getHost() == null
                || !uri.getScheme().equalsIgnoreCase("https") && !uri.getScheme().equalsIgnoreCase("http")) {
            throw ApiException.validation("url: an https:// address of the spec (https://api.acme.com/openapi.json)");
        }
        if (uri.getRawUserInfo() != null) {
            throw ApiException.validation("url: no user or password in the address; an administrator sets a host's credential in specHosts");
        }
        return uri;
    }

    /** The file name in the project: the URL's last segment when it names a spec file, else from the content. */
    static String name(URI uri, String contentType, byte[] body) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (last.matches("[A-Za-z0-9._-]+\\.(json|ya?ml)")) {
            return last;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        String start = new String(body, 0, Math.min(body.length, 64), java.nio.charset.StandardCharsets.UTF_8).stripLeading();
        return type.contains("json") || start.startsWith("{") ? "openapi.json" : "openapi.yaml";
    }

    private static void close(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException e) {
            // nothing to do
        }
    }
}
