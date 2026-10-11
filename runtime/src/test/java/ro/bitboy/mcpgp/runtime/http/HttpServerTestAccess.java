package ro.bitboy.mcpgp.runtime.http;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.http.HttpResponse;

/** HttpServerTest's reading of JSON and SSE answers, for tests in other packages. */
public final class HttpServerTestAccess {

    private HttpServerTestAccess() {
    }

    public static JsonNode message(HttpResponse<String> response) throws IOException {
        return HttpServerTest.message(response);
    }
}
