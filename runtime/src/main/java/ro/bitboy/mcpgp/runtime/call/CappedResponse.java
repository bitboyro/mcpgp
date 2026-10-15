package ro.bitboy.mcpgp.runtime.call;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

/**
 * A target's answer read up to a limit: a target that answers with gigabytes can't exhaust the
 * server's memory. Past the limit, reading stops and the connection is closed; {@link #cut()} says so.
 */
record CappedResponse(HttpResponse<?> base, byte[] body, boolean cut) implements HttpResponse<byte[]> {

    static CappedResponse read(HttpResponse<InputStream> response, int limit) throws IOException {
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes(limit + 1);
            boolean cut = bytes.length > limit;
            return new CappedResponse(response, cut ? java.util.Arrays.copyOf(bytes, limit) : bytes, cut);
        }
    }

    /** The size the target declared (Content-Length), when it did. */
    Optional<Long> declaredLength() {
        return base.headers().firstValueAsLong("Content-Length").stream().boxed().findFirst();
    }

    @Override
    public int statusCode() {
        return base.statusCode();
    }

    @Override
    public HttpRequest request() {
        return base.request();
    }

    @Override
    public Optional<HttpResponse<byte[]>> previousResponse() {
        return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
        return base.headers();
    }

    @Override
    public Optional<SSLSession> sslSession() {
        return base.sslSession();
    }

    @Override
    public URI uri() {
        return base.uri();
    }

    @Override
    public HttpClient.Version version() {
        return base.version();
    }
}
