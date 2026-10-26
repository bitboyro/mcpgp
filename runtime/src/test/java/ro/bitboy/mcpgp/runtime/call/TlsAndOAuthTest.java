package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.TestCerts;
import ro.bitboy.mcpgp.runtime.config.ConfigException;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.mcp.McpServerFactory;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Per-instance TLS, client certificates and OAuth 2 client credentials against HTTPS mock targets. */
class TlsAndOAuthTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Manifest ORDERS = Manifest.file(REPO.resolve("examples/manifest/orders-mcp.manifest.json"));
    static final Manifest SINGLE = Manifest.file(REPO.resolve("conformance/cases/content-types/multipart-split/expected/manifest.json"));
    static final TestCerts CERTS = TestCerts.get();
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;
    MockTarget target;

    @AfterEach
    void stop() {
        if (target != null) {
            target.close();
        }
    }

    McpServerFactory server(Manifest manifest, String instanceYaml, Map<String, String> env) throws IOException {
        Path file = dir.resolve("server.yaml");
        Files.writeString(file, instanceYaml);
        return new McpServerFactory(manifest, new ConfigLoader(new Schemas()).load(manifest,
                new ConfigLoader.Options(Optional.of(file), dir, env, Map.of("storage.root", dir.resolve("data").toString()))));
    }

    McpServerFactory orders(String tls) throws IOException {
        return server(ORDERS, "instances:\n  - { id: a, baseUrl: \"" + target.address() + "\"" + (tls.isEmpty() ? "" : ", tls: " + tls) + " }\n", Map.of());
    }

    static McpSchema.CallToolResult get(McpServerFactory server) {
        return server.call(null, new McpSchema.CallToolRequest("orders_customer_get", Map.of("instance", "a", "path", Map.of("id", "1")), null));
    }

    static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    @Test
    void trustsOnlyTheConfiguredCa() throws Exception {
        target = MockTarget.https(CERTS.serverP12, null);
        assertThat(get(orders("{ ca: { path: " + CERTS.serverPem + " } }")).isError()).isFalse();
        assertThat(get(orders("{ ca: { base64: " + TestCerts.base64(CERTS.serverPem) + " }, trust: ca-only }")).isError()).isFalse();
        // the system trust store doesn't know the self-signed test certificate
        McpSchema.CallToolResult system = get(orders(""));
        assertThat(system.isError()).isTrue();
        assertThat(text(system)).startsWith("UPSTREAM_UNREACHABLE: TLS connection to a failed");
        // a CA for another host doesn't validate this chain
        assertThat(get(orders("{ ca: { path: " + CERTS.otherHostPem + " } }")).isError()).isTrue();
    }

    @Test
    void hostnameCheckCanBeSkippedButTheChainIsStillValidated() throws Exception {
        target = MockTarget.https(CERTS.otherHostP12, null);       // certificate for other.example, served on 127.0.0.1
        McpSchema.CallToolResult strict = get(orders("{ ca: { path: " + CERTS.otherHostPem + " } }"));
        assertThat(strict.isError()).isTrue();
        assertThat(get(orders("{ ca: { path: " + CERTS.otherHostPem + " }, insecureSkipHostnameVerify: true }")).isError()).isFalse();
        // skipping the hostname never means trusting an unknown chain
        assertThat(get(orders("{ ca: { path: " + CERTS.serverPem + " }, insecureSkipHostnameVerify: true }")).isError()).isTrue();
    }

    @Test
    void presentsTheClientCertificate() throws Exception {
        target = MockTarget.https(CERTS.serverP12, CERTS.clientPem);
        String ca = "ca: { path: " + CERTS.serverPem + " }";
        assertThat(get(orders("{ " + ca + " }")).isError()).as("without a client certificate").isTrue();
        assertThat(get(server(ORDERS, "instances:\n  - { id: a, baseUrl: \"" + target.address() + "\", tls: { " + ca
                + ", clientCert: { path: " + CERTS.clientP12 + " }, password: \"${env:P12}\" } }\n", Map.of("P12", TestCerts.PASSWORD)))
                .isError()).as("PKCS#12").isFalse();
        assertThat(get(server(ORDERS, "instances:\n  - { id: a, baseUrl: \"" + target.address() + "\", tls: { " + ca
                + ", clientCert: { path: " + CERTS.clientPem + " }, clientKey: { base64: \"${env:KEY_B64}\" } } }\n",
                Map.of("KEY_B64", TestCerts.base64(CERTS.clientKeyPem)))).isError()).as("PEM + key").isFalse();
    }

    @Test
    void reloadsRotatedCertificateFilesOnce() throws Exception {
        target = MockTarget.https(CERTS.serverP12, null);
        Path ca = dir.resolve("ca.pem");
        Files.copy(CERTS.otherHostPem, ca);
        McpServerFactory server = orders("{ ca: { path: " + ca + " } }");
        assertThat(get(server).isError()).isTrue();
        Files.copy(CERTS.serverPem, ca, java.nio.file.StandardCopyOption.REPLACE_EXISTING);   // the operator rotates the file
        assertThat(get(server).isError()).isFalse();
    }

    @Test
    void unusableMaterialStopsStartup() throws Exception {
        target = new MockTarget();
        assertThatThrownBy(() -> orders("{ ca: { path: missing.pem } }")).isInstanceOf(ConfigException.class)
                .hasMessageContaining("/instances/0/tls/ca/path: cannot read missing.pem");
        assertThatThrownBy(() -> orders("{ ca: { base64: bm90IGEgY2VydA== } }"))
                .hasMessageContaining("/instances/0/tls/ca: not a PEM or DER certificate");
        assertThatThrownBy(() -> orders("{ trust: ca-and-system }")).hasMessageContaining("'ca-and-system' needs tls.ca");
        assertThatThrownBy(() -> orders("{ clientCert: { path: " + CERTS.clientP12 + " }, password: \"${env:NOPE}\" }"))
                .hasMessageContaining("NOPE is not set");
        // a client certificate needs a build that supports mtls
        assertThatThrownBy(() -> server(SINGLE, "instance: { baseUrl: x.example, tls: { clientCert: { path: " + CERTS.clientP12 + " } } }\n", Map.of()))
                .hasMessageContaining("/instance/tls/clientCert: client certificates (mtls) are not supported");
    }

    @Test
    void oauthClientCredentials() throws Exception {
        target = new MockTarget();
        String config = """
                instances:
                  - id: a
                    baseUrl: "%1$s"
                    contract: "2.0"
                    auth: { type: oauth2ClientCredentials, tokenUrl: "%1$s/oauth2/token", clientId: "app 1",
                            clientSecret: "${env:CS}", scopes: [orders.read, orders.write] }
                """.formatted(target.address());
        McpServerFactory server = server(ORDERS, config, Map.of("CS", "s3cr3t:x"));
        target.respond(MockTarget.Response.json(200, "{\"access_token\":\"tok-AAAA\",\"token_type\":\"Bearer\",\"expires_in\":3600}"));
        target.respond(MockTarget.Response.json(200, "{\"id\":\"1\"}"));
        target.respond(MockTarget.Response.json(200, "{\"id\":\"1\"}"));
        assertThat(get(server).isError()).isFalse();
        assertThat(get(server).isError()).isFalse();
        assertThat(target.received).hasSize(3);       // one token request for two calls

        MockTarget.Received token = target.received.get(0);
        assertThat(token.path()).isEqualTo("/oauth2/token");
        assertThat(token.header("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
        assertThat(URLDecoder.decode(token.text(), StandardCharsets.UTF_8)).isEqualTo("grant_type=client_credentials&scope=orders.read orders.write");
        String basic = new String(java.util.Base64.getDecoder().decode(token.header("Authorization").substring("Basic ".length())));
        assertThat(basic).isEqualTo("app+1:s3cr3t%3Ax");       // RFC 6749 §2.3.1: form-encoded before Basic
        assertThat(target.received.get(1).header("Authorization")).isEqualTo("Bearer tok-AAAA");

        // 401: the token is discarded, a new one requested, the call retried once
        target.respond(MockTarget.Response.json(401, "{}"));
        target.respond(MockTarget.Response.json(200, "{\"access_token\":\"tok-BBBB\",\"expires_in\":3600}"));
        target.respond(MockTarget.Response.json(401, "{\"error\":\"bad token tok-BBBB\"}"));
        McpSchema.CallToolResult refused = get(server);
        assertThat(target.received).hasSize(6);
        assertThat(target.received.get(5).header("Authorization")).isEqualTo("Bearer tok-BBBB");
        assertThat(text(refused)).startsWith("AUTH_UPSTREAM").contains("bad token ***").doesNotContain("tok-BBBB");
    }

    @Test
    void tokenEndpointErrorsNameTheErrorNotTheSecret() throws Exception {
        target = new MockTarget();
        McpServerFactory server = server(ORDERS, """
                instances:
                  - { id: a, baseUrl: "%1$s", contract: "2.0", auth: { type: oauth2ClientCredentials, tokenUrl: "%1$s/token", clientId: c,
                      clientSecret: "${env:CS}", clientAuth: post } }
                """.formatted(target.address()), Map.of("CS", "very-secret"));
        target.respond(MockTarget.Response.json(400, "{\"error\":\"invalid_client\",\"error_description\":\"secret very-secret is wrong\"}"));
        McpSchema.CallToolResult result = get(server);
        assertThat(text(result)).startsWith("AUTH_UPSTREAM: token request for a was refused: invalid_client").doesNotContain("very-secret");
        assertThat(URLDecoder.decode(target.last().text(), StandardCharsets.UTF_8)).contains("client_id=c").contains("client_secret=very-secret");
        assertThat(target.last().header("Authorization")).isNull();
        JsonNode structured = JSON.valueToTree(result.structuredContent());
        assertThat(structured.path("code").asText()).isEqualTo("AUTH_UPSTREAM");
    }
}
