package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Servers from an API's spec (spec/platform-api.md §2.1): one file or several, checked before anything is created. */
class FromSpecApiTest extends PlatformTestBase {

    static final String SWAGGER = """
            { "swagger": "2.0", "info": { "title": "Ledger API", "version": "1.2" }, "basePath": "/v1",
              "paths": { "/entries": { "$ref": "paths/entries.json" } },
              "definitions": { "Entry": { "$ref": "definitions/entry.json#/Entry" } } }
            """;
    static final String ENTRIES = """
            { "get": { "operationId": "listEntries", "summary": "List entries",
                       "responses": { "200": { "description": "ok", "schema": { "type": "array", "items": { "$ref": "../definitions/entry.json#/Entry" } } } } } }
            """;
    static final String ENTRY = "{ \"Entry\": { \"type\": \"object\", \"properties\": { \"id\": { \"type\": \"string\" } } } }";

    static HttpRequest.Builder post(String path, Object files) throws Exception {
        return bearer("POST", path, JSON.writeValueAsBytes(Map.of("files", files)));
    }

    @Test
    void aServerFromASwaggerJsonAndTheFilesItRefersTo() throws Exception {
        Map<String, String> files = Map.of("api.json", SWAGGER, "paths/entries.json", ENTRIES, "definitions/entry.json", ENTRY);

        JsonNode dry = json(send(post("/api/v1/servers/from-spec?dryRun=true", files)));
        assertThat(dry.path("id").asText()).as(dry.toString()).isEqualTo("ledger-mcp");
        assertThat(dry.path("api").asText()).isEqualTo("ledger");
        assertThat(dry.path("version").asText()).isEqualTo("1.2");
        assertThat(dry.path("format").asText()).isEqualTo("swagger-2.0");
        assertThat(dry.path("tools").asInt()).isEqualTo(1);
        assertThat(dry.path("files").toString()).contains("contracts/api.json").contains("contracts/paths/entries.json")
                .contains("ledger-mcp.mcpgp.yaml").contains("tests/smoke.yaml");
        assertThat(send(bearer("GET", "/api/v1/servers/ledger-mcp", null)).statusCode()).isEqualTo(404);   // a dry run creates nothing

        var created = send(post("/api/v1/servers/from-spec", files));
        assertThat(created.statusCode()).as(new String(created.body())).isEqualTo(201);
        JsonNode manifest = json(send(bearer("GET", "/api/v1/servers/ledger-mcp/versions/latest/manifest", null)));
        assertThat(manifest.path("tools").findValuesAsText("name")).contains("ledger_entry_list");

        // a new contract version, as the default: its files under contracts/2.0/
        String v2 = SWAGGER.replace("\"version\": \"1.2\"", "\"version\": \"2.0\"");
        var added = send(post("/api/v1/servers/ledger-mcp/contracts?default=true", Map.of("api.json", v2, "paths/entries.json", ENTRIES,
                "definitions/entry.json", ENTRY)));
        assertThat(added.statusCode()).as(new String(added.body())).isEqualTo(201);
        JsonNode definition = json(send(bearer("GET", "/api/v1/servers/ledger-mcp/versions/latest/definition", null)));
        JsonNode contracts = definition.at("/root/apis/0/contracts");
        assertThat(contracts).hasSize(2);
        assertThat(contracts.toString()).contains("\"spec\":\"contracts/2.0/api.json\"").contains("\"default\":true");

        // the same version again replaces its spec
        JsonNode again = json(send(post("/api/v1/servers/ledger-mcp/contracts?dryRun=true", Map.of("api.json", v2, "paths/entries.json", ENTRIES,
                "definitions/entry.json", ENTRY))));
        assertThat(again.path("replaces").asBoolean()).isTrue();
        assertThat(again.path("compiles").asBoolean()).isTrue();
    }

    @Test
    void saysWhatIsMissingBeforeCreatingAnything() throws Exception {
        var missing = send(post("/api/v1/servers/from-spec", Map.of("api.json", SWAGGER, "paths/entries.json", ENTRIES)));
        assertThat(missing.statusCode()).isEqualTo(422);
        JsonNode problem = json(missing);
        assertThat(problem.path("code").asText()).isEqualTo("SPEC_PROBLEMS");
        assertThat(problem.at("/missing/0/file").asText()).isEqualTo("definitions/entry.json");
        assertThat(problem.path("detail").asText()).contains("definitions/entry.json is missing").contains("add it to the upload");

        String remote = "openapi: 3.0.3\ninfo: { title: Remote, version: '1' }\npaths:\n  /x:\n    $ref: 'https://example.com/paths.yaml'\n";
        JsonNode url = json(send(post("/api/v1/servers/from-spec", Map.of("openapi.yaml", remote))));
        assertThat(url.at("/remote/0/file").asText()).isEqualTo("https://example.com/paths.yaml");
        assertThat(url.path("detail").asText()).contains("doesn't fetch URLs");

        JsonNode two = json(send(post("/api/v1/servers/from-spec", Map.of(
                "a.yaml", "openapi: 3.0.3\ninfo: { title: A, version: '1' }\npaths: {}\n",
                "b.yaml", "openapi: 3.0.3\ninfo: { title: B, version: '1' }\npaths: {}\n"))));
        assertThat(two.path("candidates").toString()).contains("a.yaml").contains("b.yaml");

        assertThat(send(post("/api/v1/servers/from-spec", Map.of("a.yaml", "openapi: 3.2.0\ninfo: { title: A, version: '1' }\n"))).statusCode())
                .isEqualTo(422);

        // the file is there, but not the part the reference points to: said before anything is created
        var part = send(post("/api/v1/servers/from-spec", Map.of("api.json", SWAGGER, "paths/entries.json", ENTRIES,
                "definitions/entry.json", "{ \"Other\": { \"type\": \"object\" } }")));
        assertThat(part.statusCode()).isEqualTo(422);
        assertThat(json(part).path("detail").asText()).contains("definitions/entry.json#/Entry").contains("isn't there");
        assertThat(json(part).path("broken")).hasSize(2);     // both places that use it
        // and so for a reference inside the spec itself
        var inside = send(post("/api/v1/servers/from-spec", Map.of("openapi.yaml", """
                openapi: 3.0.3
                info: { title: Inside, version: '1' }
                paths:
                  /x:
                    get:
                      responses: { "200": { description: ok, content: { application/json: { schema: { $ref: '#/components/schemas/Nope' } } } } }
                """)));
        assertThat(inside.statusCode()).isEqualTo(422);
        assertThat(json(inside).path("detail").asText()).contains("openapi.yaml#/components/schemas/Nope").contains("isn't there");
        assertThat(json(inside).at("/broken/0/pointer").asText()).isEqualTo("/paths/~1x/get/responses/200/content/application~1json/schema/$ref");
    }

    @Test
    void aZipOfTheSpecFolderWorksToo() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var f : Map.of("ledger/api.json", SWAGGER, "ledger/paths/entries.json", ENTRIES, "ledger/definitions/entry.json", ENTRY,
                    "ledger/README.md", "# not part of the spec").entrySet()) {
                zip.putNextEntry(new ZipEntry(f.getKey()));
                zip.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        var created = send(HttpRequest.newBuilder(java.net.URI.create(root + "/api/v1/servers/from-spec?id=ledger-zip&title=Ledger%20from%20a%20zip"))
                .header("Authorization", "Bearer " + admin).header("Content-Type", "application/zip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())));
        assertThat(created.statusCode()).as(new String(created.body())).isEqualTo(201);
        assertThat(json(created).path("title").asText()).isEqualTo("Ledger from a zip");
        JsonNode version = json(send(bearer("GET", "/api/v1/servers/ledger-zip/versions/latest", null)));
        assertThat(version.path("files").toString()).contains("contracts/api.json").doesNotContain("README.md");
    }
}
