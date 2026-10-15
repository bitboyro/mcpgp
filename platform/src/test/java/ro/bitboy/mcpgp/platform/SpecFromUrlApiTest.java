package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** A spec version fetched from where the API publishes it (platform-api.md §2.1), from allowed hosts only. */
class SpecFromUrlApiTest extends PlatformTestBase {

    static final String TOKEN = "spec-token-not-to-be-shown";
    static HttpServer PUBLISHER;
    static final List<String> SEEN = new CopyOnWriteArrayList<>();
    static volatile String published;

    static String spec(String version, boolean withEntry) {
        return """
                openapi: 3.0.3
                info: { title: Ledger, version: "%s" }
                paths:
                  /entries:
                    get: { operationId: listEntries, summary: List entries, responses: { "200": { description: ok } } }
                """.formatted(version) + (withEntry ? """
                  /entries/{id}:
                    get:
                      operationId: getEntry
                      summary: Get an entry
                      parameters: [ { name: id, in: path, required: true, schema: { type: string } } ]
                      responses: { "200": { description: ok } }
                """ : "");
    }

    static {
        try {
            PUBLISHER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            Path token = Files.createTempFile("spec-token", ".txt");
            Files.writeString(token, TOKEN);
            token.toFile().deleteOnExit();
            PUBLISHER.createContext("/", x -> {
                SEEN.add(x.getRequestURI().getPath() + " " + x.getRequestHeaders().getFirst("X-Spec-Token"));
                byte[] body = published.getBytes(StandardCharsets.UTF_8);
                if (x.getRequestURI().getPath().equals("/moved")) {
                    x.getResponseHeaders().add("Location", "https://elsewhere.example/openapi.yaml");
                    x.sendResponseHeaders(302, -1);
                } else if (!TOKEN.equals(x.getRequestHeaders().getFirst("X-Spec-Token"))) {
                    x.sendResponseHeaders(401, -1);
                } else {
                    x.getResponseHeaders().add("Content-Type", "application/yaml");
                    x.sendResponseHeaders(200, body.length);
                    x.getResponseBody().write(body);
                }
                x.close();
            });
            PUBLISHER.start();
            configure = c -> c.withSpecHosts(List.of(new PlatformConfig.SpecHost("127.0.0.1:" + PUBLISHER.getAddress().getPort(), true,
                    "X-Spec-Token", "${file:" + token + "}")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void stopPublisher() {
        PUBLISHER.stop(0);
    }

    static String address(String path) {
        return "http://127.0.0.1:" + PUBLISHER.getAddress().getPort() + path;
    }

    static HttpResponse<byte[]> contracts(String query) throws Exception {
        return send(bearer("POST", "/api/v1/servers/ledger-url/contracts?" + query, null));
    }

    static String q(String url) {
        return java.net.URLEncoder.encode(url, StandardCharsets.UTF_8);
    }

    @Test
    void aVersionIsFetchedFromItsAddressAndCheckedForUpdates() throws Exception {
        assertThat(send(bearer("POST", "/api/v1/servers/from-spec?id=ledger-url", JSON.writeValueAsBytes(Map.of("files",
                Map.of("openapi.yaml", spec("1.0", false)))))).statusCode()).isEqualTo(201);

        // a new version, published at an allowed address
        published = spec("2.0", false);
        JsonNode plan = json(contracts("dryRun=true&url=" + q(address("/specs/ledger.yaml"))));
        assertThat(plan.path("version").asText()).as(plan.toString()).isEqualTo("2.0");
        assertThat(plan.path("compiles").asBoolean()).isTrue();
        assertThat(plan.path("unchanged").asBoolean()).isFalse();
        assertThat(plan.path("source").asText()).isEqualTo(address("/specs/ledger.yaml"));
        assertThat(plan.at("/fetched/file").asText()).isEqualTo("ledger.yaml");
        assertThat(plan.path("spec").asText()).isEqualTo("contracts/2.0/ledger.yaml");
        assertThat(plan.has("summary")).isTrue();
        assertThat(SEEN).as("the host's credential is sent").contains("/specs/ledger.yaml " + TOKEN);

        HttpResponse<byte[]> saved = contracts("url=" + q(address("/specs/ledger.yaml")));
        assertThat(saved.statusCode()).as(new String(saved.body())).isEqualTo(201);
        int version = json(saved).path("latestVersion").asInt();
        JsonNode definition = json(send(bearer("GET", "/api/v1/servers/ledger-url/versions/latest/definition", null)));
        assertThat(definition.toString()).contains("\"source\":\"" + address("/specs/ledger.yaml") + "\"");

        // checking again: nothing changed, nothing saved
        JsonNode same = json(contracts("dryRun=true&source=true&version=2.0"));
        assertThat(same.path("unchanged").asBoolean()).as(same.toString()).isTrue();
        HttpResponse<byte[]> again = contracts("source=true&version=2.0");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(json(again).path("latestVersion").asInt()).isEqualTo(version);

        // the publisher adds an operation: the check says what agents get
        published = spec("2.0", true);
        JsonNode update = json(contracts("dryRun=true&source=true&version=2.0"));
        assertThat(update.path("unchanged").asBoolean()).isFalse();
        assertThat(update.path("replaces").asBoolean()).isTrue();
        assertThat(update.path("changes").toString()).contains("ledger_entry_get");
        assertThat(contracts("source=true&version=2.0").statusCode()).isEqualTo(201);

        // nothing the platform answers shows the credential
        assertThat(new String(send(bearer("GET", "/api/v1/servers/ledger-url/versions/latest/definition", null)).body())).doesNotContain(TOKEN);
        assertThat(new String(send(bearer("GET", "/api/v1/activity", null)).body())).doesNotContain(TOKEN);
    }

    @Test
    void onlyListedHostsAreFetchedFrom() throws Exception {
        assertThat(send(bearer("POST", "/api/v1/servers/from-spec?id=ledger-hosts", JSON.writeValueAsBytes(Map.of("files",
                Map.of("openapi.yaml", spec("1.0", false)))))).statusCode()).isEqualTo(201);
        published = spec("2.0", false);
        int before = SEEN.size();

        JsonNode other = json(send(bearer("POST", "/api/v1/servers/ledger-hosts/contracts?dryRun=true&url=" + q("https://api.example.com/openapi.json"), null)));
        assertThat(other.path("code").asText()).isEqualTo("SPEC_HOST_NOT_ALLOWED");
        assertThat(other.path("detail").asText()).contains("api.example.com isn't in the platform's specHosts");

        JsonNode https = json(send(bearer("POST", "/api/v1/servers/ledger-hosts/contracts?dryRun=true&url="
                + q("http://127.0.0.1:1/openapi.json"), null)));
        assertThat(https.path("code").asText()).as("another port of a listed host").isEqualTo("SPEC_HOST_NOT_ALLOWED");

        JsonNode moved = json(send(bearer("POST", "/api/v1/servers/ledger-hosts/contracts?dryRun=true&url=" + q(address("/moved")), null)));
        assertThat(moved.path("code").asText()).as("a redirect to a host that isn't listed").isEqualTo("SPEC_HOST_NOT_ALLOWED");
        assertThat(moved.path("detail").asText()).contains("elsewhere.example");

        JsonNode both = json(send(bearer("POST", "/api/v1/servers/ledger-hosts/contracts?url=" + q(address("/x.yaml")),
                JSON.writeValueAsBytes(Map.of("files", Map.of("openapi.yaml", spec("3.0", false)))))));
        assertThat(both.path("detail").asText()).contains("not both");

        JsonNode uploaded = json(send(bearer("POST", "/api/v1/servers/ledger-hosts/contracts?dryRun=true&source=true&version=1.0", null)));
        assertThat(uploaded.path("detail").asText()).contains("wasn't fetched from an address");
        assertThat(SEEN.size() - before).as("only the redirecting address was asked").isEqualTo(1);
    }
}
