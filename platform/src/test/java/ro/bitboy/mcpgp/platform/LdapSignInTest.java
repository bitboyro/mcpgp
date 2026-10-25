package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sign-in against a company directory (spec/platform-api.md §3), with an in-memory LDAP server. */
class LdapSignInTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String BASE = "dc=acme,dc=com";

    @TempDir
    static Path dir;
    static InMemoryDirectoryServer ldap;
    static ConfigurableApplicationContext context;
    static String api;
    static String admin;

    @BeforeAll
    static void start() throws Exception {
        InMemoryDirectoryServerConfig directory = new InMemoryDirectoryServerConfig(BASE);
        directory.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", InetAddress.getLoopbackAddress(), 0, null));
        directory.setSchema(null);
        ldap = new InMemoryDirectoryServer(directory);
        ldap.add("dn: " + BASE, "objectClass: domain", "dc: acme");
        for (String ou : new String[] {"people", "groups", "services"}) {
            ldap.add("dn: ou=" + ou + "," + BASE, "objectClass: organizationalUnit", "ou: " + ou);
        }
        ldap.add("dn: cn=mcpgp,ou=services," + BASE, "objectClass: person", "cn: mcpgp", "sn: mcpgp", "userPassword: service secret");
        person("bo", "bo password 1", "bo@acme.com");
        person("cy", "cy password 1", "cy@acme.com");
        person("ana", "ana password 1", "ana@acme.com");
        person("dee", "dee password 1", "dee@acme.com");
        group("mcpgp-builders", "bo", "dee");
        group("mcpgp-admins", "ana");
        group("everyone", "bo", "cy", "ana", "dee");
        ldap.startListening();

        Path secret = dir.resolve("bind-password");
        Files.writeString(secret, "service secret\n");
        int port;
        try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        Path file = dir.resolve("platform.yaml");
        Files.writeString(file, """
                http: { host: 127.0.0.1, port: %d }
                storage: { root: %s }
                auth:
                  ldap:
                    url: ldap://127.0.0.1:%d/%s
                    bindDn: cn=mcpgp,ou=services,%s
                    bindPassword: "${file:%s}"
                    userSearch: { base: "ou=people", filter: "(uid={0})" }
                    groupSearch: { base: "ou=groups", filter: "(member={0})" }
                    roles: { mcpgp-builders: builder, MCPGP-ADMINS: admin }
                """.formatted(port, dir.resolve("store"), ldap.getListenPort(), BASE, BASE, secret));
        PlatformConfig config = PlatformConfig.load(file, Map.of());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // the administrator init creates is linked by name when they first sign in with the directory
        PlatformServer.init(config, new String[] {"init", "--admin", "ana"}, new PrintStream(out, true, StandardCharsets.UTF_8));
        admin = out.toString(StandardCharsets.UTF_8).lines().filter(l -> l.strip().startsWith("mcpgp_")).findFirst().orElseThrow().strip();
        context = PlatformServer.start(config);
        api = "http://127.0.0.1:" + port + "/api/v1";
    }

    static void person(String uid, String password, String mail) throws Exception {
        ldap.add("dn: uid=" + uid + ",ou=people," + BASE, "objectClass: inetOrgPerson", "uid: " + uid, "cn: " + uid, "sn: " + uid,
                "mail: " + mail, "userPassword: " + password);
    }

    static void group(String cn, String... members) throws Exception {
        String[] lines = new String[members.length + 3];
        lines[0] = "dn: cn=" + cn + ",ou=groups," + BASE;
        lines[1] = "objectClass: groupOfNames";
        lines[2] = "cn: " + cn;
        for (int i = 0; i < members.length; i++) {
            lines[i + 3] = "member: uid=" + members[i] + ",ou=people," + BASE;
        }
        ldap.add(lines);
    }

    @AfterAll
    static void stop() {
        context.close();
        ldap.shutDown(true);
    }

    static HttpResponse<byte[]> signIn(String name, String password) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(api + "/sessions")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(Map.of("name", name, "password", password)))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    static JsonNode get(String path, String token) throws Exception {
        return JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(api + path)).header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofByteArray()).body());
    }

    static JsonNode identity(String name) throws Exception {
        for (JsonNode i : get("/identities", admin).path("items")) {
            if (i.path("name").asText().equals(name)) {
                return i;
            }
        }
        return null;
    }

    @Test
    void peopleInAMappedGroupSignInAndGetItsRole() throws Exception {
        HttpResponse<byte[]> response = signIn("bo", "bo password 1");
        assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(201);
        String token = JSON.readTree(response.body()).path("value").asText();
        JsonNode me = get("/me", token);
        assertThat(me.path("name").asText()).isEqualTo("bo");
        assertThat(me.path("scopes")).extracting(JsonNode::asText).contains("servers:write", "builds:run").doesNotContain("admin", "builds:release");
        JsonNode bo = identity("bo");
        assertThat(bo.path("signsInWith").asText()).isEqualTo("ldap");
        assertThat(bo.path("rolesFromGroups").asBoolean()).isTrue();
        // the second sign-in finds the same identity
        assertThat(signIn("bo", "bo password 1").statusCode()).isEqualTo(201);
        assertThat(get("/identities", admin).path("items").findValuesAsText("name").stream().filter("bo"::equals)).hasSize(1);
    }

    @Test
    void wrongPasswordsAndPeopleInNoMappedGroupGetTheSameAnswer() throws Exception {
        HttpResponse<byte[]> wrong = signIn("cy", "not the password");
        HttpResponse<byte[]> unmapped = signIn("cy", "cy password 1");
        HttpResponse<byte[]> unknown = signIn("nobody", "whatever it is");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(unmapped.statusCode()).isEqualTo(401);
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(unmapped.body()).path("detail")).isEqualTo(JSON.readTree(wrong.body()).path("detail"));
        assertThat(identity("cy")).as("no identity for people in no mapped group").isNull();
        JsonNode denied = get("/activity?action=signin&actor=cy", admin).path("items");
        assertThat(denied.findValuesAsText("reason")).contains("no mapped group", "wrong password (directory)");
        // an empty password would be an anonymous bind, which directories accept
        assertThat(signIn("bo", "").statusCode()).isEqualTo(401);
    }

    @Test
    void theAdministratorInitCreatedIsLinkedAndKeepsTheirScopes() throws Exception {
        HttpResponse<byte[]> response = signIn("ana", "ana password 1");
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode me = get("/me", JSON.readTree(response.body()).path("value").asText());
        assertThat(me.path("id").asText()).isEqualTo(get("/me", admin).path("id").asText());
        assertThat(me.path("scopes")).extracting(JsonNode::asText).contains("admin");
        assertThat(identity("ana").path("signsInWith").asText()).isEqualTo("ldap");
        assertThat(identity("ana").has("rolesFromGroups")).isFalse();
    }

    @Test
    void leavingTheGroupEndsAccess() throws Exception {
        assertThat(signIn("dee", "dee password 1").statusCode()).isEqualTo(201);
        ldap.modify("cn=mcpgp-builders,ou=groups," + BASE, new Modification(ModificationType.DELETE, "member", "uid=dee,ou=people," + BASE));
        assertThat(signIn("dee", "dee password 1").statusCode()).isEqualTo(401);
    }

    @Test
    void repeatedFailuresLockTheNameForAWhile() throws Exception {
        person("eve", "eve password 1", "eve@acme.com");
        ldap.modify("cn=mcpgp-builders,ou=groups," + BASE, new Modification(ModificationType.ADD, "member", "uid=eve,ou=people," + BASE));
        for (int i = 0; i < 5; i++) {
            assertThat(signIn("eve", "wrong " + i).statusCode()).isEqualTo(401);
        }
        assertThat(signIn("eve", "eve password 1").statusCode()).as("locked, even with the right password").isEqualTo(401);
    }

    @Test
    void theConfigOnlyAcceptsEncryptedConnectionsAndSecretReferences() throws Exception {
        Path file = dir.resolve("bad.yaml");
        Files.writeString(file, "auth: { ldap: { url: ldap://ldap.acme.com, userSearch: { base: x }, roles: { g: admin } } }\n");
        assertThatThrownBy(() -> PlatformConfig.load(file, Map.of())).hasMessageContaining("ldaps://");
        Files.writeString(file, "auth: { ldap: { url: ldaps://ldap.acme.com, bindPassword: hunter2, userSearch: { base: x }, roles: { g: admin } } }\n");
        assertThatThrownBy(() -> PlatformConfig.load(file, Map.of())).hasMessageContaining("reference");
        Files.writeString(file, "auth: { ldap: { url: ldaps://ldap.acme.com, userSearch: { base: x }, roles: { g: owner } } }\n");
        assertThatThrownBy(() -> PlatformConfig.load(file, Map.of())).hasMessageContaining("one of");
        Files.writeString(file, "auth: { ldap: { url: ldaps://ldap.acme.com, userSearch: { base: x } } }\n");
        assertThatThrownBy(() -> PlatformConfig.load(file, Map.of())).hasMessageContaining("people in no mapped group");
        Files.writeString(file, "auth: { ldap: { url: ldap://ldap.acme.com, startTls: true, userSearch: { base: x }, roles: { g: admin } } }\n");
        assertThat(PlatformConfig.load(file, Map.of()).ldap().startTls()).isTrue();
    }

    @Test
    void theSignInPageKnowsAboutTheDirectory() throws Exception {
        JsonNode options = JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(api + "/platform")).build(),
                HttpResponse.BodyHandlers.ofByteArray()).body());
        assertThat(options.at("/auth/ldap").asBoolean()).isTrue();
        assertThat(options.at("/auth/oidc").asBoolean()).isFalse();
    }
}
