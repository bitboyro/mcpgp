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

    @Test
    void everyBuiltInTemplateCreatesACompilableServer() throws Exception {
        Map<String, String> files = Map.of("api.json", SWAGGER, "paths/entries.json", ENTRIES, "definitions/entry.json", ENTRY);
        for (String template : new String[] {"blank", "default", "personal-use", "enterprise-grade"}) {
            String id = "preset-" + template;
            assertThat(send(post("/api/v1/servers/from-spec?id=" + id + "&template=" + template + "&acceptTemplate=" + template + "@1", files)).statusCode()).as(template).isEqualTo(201);
            JsonNode validation = json(send(bearer("POST", "/api/v1/servers/" + id + "/versions/latest/validate", null)));
            assertThat(validation.path("ok").asBoolean()).as(template + ": " + validation).isTrue();
        }
    }

    @Test
    void templatesArePreviewedAndMustBeAcceptedBeforeImport() throws Exception {
        Map<String, String> files = Map.of("api.json", SWAGGER, "paths/entries.json", ENTRIES, "definitions/entry.json", ENTRY);
        JsonNode list = json(send(bearer("GET", "/api/v1/servers/configuration-templates", null)));
        assertThat(list.path("items").findValuesAsText("id")).contains("blank", "default", "personal-use", "enterprise-grade");

        JsonNode preview = json(send(post("/api/v1/servers/from-spec?dryRun=true&id=blank-mcp&template=blank", files)));
        assertThat(preview.at("/template/id").asText()).isEqualTo("blank");
        assertThat(preview.path("acceptTemplate").asText()).isEqualTo("blank@1");
        assertThat(send(post("/api/v1/servers/from-spec?id=blank-mcp&template=blank", files)).statusCode()).isEqualTo(422);
        assertThat(send(post("/api/v1/servers/from-spec?id=blank-mcp&template=blank&acceptTemplate=blank@1", files)).statusCode()).isEqualTo(201);
        String definition = new String(send(bearer("GET", "/api/v1/servers/blank-mcp/versions/latest/files/blank-mcp.mcpgp.yaml", null)).body(), StandardCharsets.UTF_8);
        assertThat(definition).contains("runtimeDefaults").contains("configuration").contains("allow");
    }

    @Test
    void aVersionedLibraryTemplateIsAvailableToImport() throws Exception {
        String template = """
                title: Workspace safe defaults
                description: Local policy
                configuration:
                  tools: { allow: [] }
                requiredInputs: [tool selection]
                """;
        assertThat(send(bearer("POST", "/api/v1/library/templates?name=workspace-safe",
                JSON.writeValueAsBytes(Map.of("files", Map.of("workspace-safe.yaml", template))))).statusCode()).isEqualTo(201);
        Map<String, String> files = Map.of("api.json", SWAGGER, "paths/entries.json", ENTRIES, "definitions/entry.json", ENTRY);
        JsonNode preview = json(send(post("/api/v1/servers/from-spec?dryRun=true&id=library-template-mcp&template=library/workspace-safe@1", files)));
        assertThat(preview.at("/template/id").asText()).isEqualTo("library/workspace-safe@1");
        assertThat(preview.path("acceptTemplate").asText()).isEqualTo("library/workspace-safe@1");
    }

    @Test
    void anExistingServerPreviewsAndExplicitlyAppliesATemplate() throws Exception {
        Map<String, String> files = Map.of("api.json", SWAGGER, "paths/entries.json", ENTRIES, "definitions/entry.json", ENTRY);
        assertThat(send(post("/api/v1/servers/from-spec?id=upgrade-template-mcp", files)).statusCode()).isEqualTo(201);
        JsonNode preview = json(send(bearer("POST", "/api/v1/servers/upgrade-template-mcp/configuration-template?base=1&template=blank&dryRun=true", null)));
        assertThat(preview.path("acceptTemplate").asText()).isEqualTo("blank@1");
        assertThat(send(bearer("POST", "/api/v1/servers/upgrade-template-mcp/configuration-template?base=1&template=blank", null)).statusCode()).isEqualTo(422);
        assertThat(send(bearer("POST", "/api/v1/servers/upgrade-template-mcp/configuration-template?base=1&template=blank&acceptTemplate=blank@1", null)).statusCode()).isEqualTo(201);
    }

    static HttpRequest.Builder post(String path, Object files) throws Exception {
        return bearer("POST", path, JSON.writeValueAsBytes(Map.of("files", files)));
    }

    static final String CLASHING = """
            openapi: 3.0.3
            info: { title: Trading Configuration, version: "1.0" }
            paths:
              /partners/{identifier}:
                put:
                  operationId: partnerIdentifierPut
                  parameters: [ { name: identifier, in: path, required: true, schema: { type: string } } ]
                  responses: { "200": { description: ok } }
              /tests/{identifier}:
                put:
                  operationId: partnerIdentifierPut
                  parameters: [ { name: identifier, in: path, required: true, schema: { type: string } } ]
                  responses: { "200": { description: ok } }
            """;

    @Test
    void clashingToolNamesAreShownWithSuggestionsAndResolvedAsTheUserChooses() throws Exception {
        Map<String, String> files = Map.of("tc.yaml", CLASHING);
        var clash = send(post("/api/v1/servers/from-spec?dryRun=true", files));
        assertThat(clash.statusCode()).isEqualTo(422);
        JsonNode problem = json(clash);
        assertThat(problem.path("code").asText()).isEqualTo("NAME_COLLISIONS");
        JsonNode first = problem.at("/collisions/0");
        assertThat(first.path("operation").asText()).isEqualTo("trading_configuration:PUT:/tests/{identifier}");
        assertThat(first.path("name").asText()).isEqualTo("trading_configuration_partner_identifier_put");
        assertThat(first.path("suggested").asText()).isEqualTo("trading_configuration_test_replace");
        assertThat(first.path("base").asText()).isEqualTo("test_replace");

        // a rule for the server
        assertThat(send(post("/api/v1/servers/from-spec?dryRun=true&collisions=method-path", files)).statusCode()).isEqualTo(200);

        // or a name of their own, saved as a rule for that operation
        String renames = java.net.URLEncoder.encode("{\"trading_configuration:PUT:/tests/{identifier}\": \"test_update\"}", StandardCharsets.UTF_8);
        var created = send(post("/api/v1/servers/from-spec?id=tc-mcp&renames=" + renames, files));
        assertThat(created.statusCode()).as(new String(created.body())).isEqualTo(201);
        JsonNode manifest = json(send(bearer("GET", "/api/v1/servers/tc-mcp/versions/latest/manifest", null)));
        assertThat(manifest.path("tools").findValuesAsText("name")).contains("trading_configuration_partner_identifier_put", "trading_configuration_test_update");
        String definition = new String(send(bearer("GET", "/api/v1/servers/tc-mcp/versions/latest/files/tc-mcp.mcpgp.yaml", null)).body(), StandardCharsets.UTF_8);
        assertThat(definition).contains("match: { method: PUT, path: \"/tests/{identifier}\" }").contains("name: { set: test_update }");
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
