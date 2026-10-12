package ro.bitboy.mcpgp.core.scaffold;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpecFilesTest {

    static Map<String, byte[]> files(String... pathsAndContents) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            files.put(pathsAndContents[i], pathsAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return files;
    }

    static final String SWAGGER = """
            { "swagger": "2.0", "info": { "title": "Orders API", "version": "1.4" },
              "paths": { "/customers": { "$ref": "paths/customers.json" } },
              "definitions": { "Customer": { "$ref": "definitions/customer.json#/Customer" } } }
            """;

    @Test
    void aSwaggerJsonWithItsReferencedFiles() {
        SpecFiles.Analysis a = SpecFiles.analyze(files(
                "api.json", SWAGGER,
                "paths/customers.json", "{ \"get\": { \"responses\": { \"200\": { \"schema\": { \"$ref\": \"../definitions/customer.json#/Customer\" } } } } }",
                "definitions/customer.json", "{ \"Customer\": { \"type\": \"object\" } }"), null);
        assertThat(a.ok()).as("%s", a).isTrue();
        assertThat(a.main()).isEqualTo("api.json");
        assertThat(a.format()).isEqualTo("swagger-2.0");
        assertThat(a.title()).isEqualTo("Orders API");
        assertThat(a.version()).isEqualTo("1.4");
        assertThat(a.used()).containsExactly("api.json", "paths/customers.json", "definitions/customer.json");
    }

    @Test
    void saysWhichFilesAreMissingAndWhichAreUrls() {
        String spec = """
                openapi: 3.1.0
                info: { title: Billing, version: "2.0" }
                paths:
                  /invoices:
                    get:
                      responses:
                        "200": { content: { application/json: { schema: { $ref: "./schemas/invoice.yaml" } } } }
                        "404": { $ref: "https://example.com/errors.yaml#/NotFound" }
                """;
        SpecFiles.Analysis a = SpecFiles.analyze(files("openapi.yaml", spec), null);
        assertThat(a.ok()).isFalse();
        assertThat(a.missing()).singleElement().satisfies(m -> {
            assertThat(m.target()).isEqualTo("schemas/invoice.yaml");
            assertThat(m.from()).isEqualTo("openapi.yaml");
            assertThat(m.pointer()).isEqualTo("/paths/~1invoices/get/responses/200/content/application~1json/schema/$ref");
        });
        assertThat(a.remote()).singleElement().extracting(SpecFiles.Ref::target).isEqualTo("https://example.com/errors.yaml");
    }

    @Test
    void asksWhenSeveralFilesCouldBeTheApi() {
        SpecFiles.Analysis a = SpecFiles.analyze(files(
                "v1.yaml", "openapi: 3.0.3\ninfo: { title: A, version: '1' }\npaths: {}\n",
                "v2.yaml", "openapi: 3.0.3\ninfo: { title: A, version: '2' }\npaths: {}\n"), null);
        assertThat(a.main()).isNull();
        assertThat(a.candidates()).containsExactly("v1.yaml", "v2.yaml");
        assertThat(SpecFiles.analyze(files(
                "v1.yaml", "openapi: 3.0.3\ninfo: { title: A, version: '1' }\npaths: {}\n",
                "v2.yaml", "openapi: 3.0.3\ninfo: { title: A, version: '2' }\npaths: {}\n"), "v2.yaml").version()).isEqualTo("2");
    }

    @Test
    void refusesWhatTheCompilerDoesntRead() {
        assertThat(SpecFiles.analyze(files("a.yaml", "openapi: 3.2.0\ninfo: { title: A, version: '1' }\n"), null).problems())
                .singleElement().asString().contains("3.2.0 isn't supported yet");
        assertThat(SpecFiles.analyze(files("notes.yaml", "hello: world\n"), null).problems())
                .singleElement().asString().contains("none of the files is an OpenAPI or Swagger document");
        assertThat(SpecFiles.analyze(files("a.yaml", "openapi: 3.0.3\ninfo: {title: A, version: '1'}\npaths: { /x: { $ref: '../../etc/x.yaml' } }\n"), null)
                .problems()).singleElement().asString().contains("outside the uploaded files");
        assertThat(SpecFiles.specFiles(files("__MACOSX/a.json", "{}", ".DS_Store", "x", "README.md", "#", "api.json", "{}"))).containsOnlyKeys("api.json");
    }

    @Test
    void filesPickedWithoutTheirFoldersGoWhereTheSpecExpectsThem() {
        SpecFiles.Placed placed = SpecFiles.place(files(
                "api.json", SWAGGER,
                "customers.json", "{ \"get\": { \"responses\": { \"200\": { \"schema\": { \"$ref\": \"../definitions/customer.json#/Customer\" } } } } }",
                "customer.json", "{ \"Customer\": { \"type\": \"object\" } }"), null);
        assertThat(placed.moves()).extracting(SpecFiles.Move::to).containsExactlyInAnyOrder("paths/customers.json", "definitions/customer.json");
        assertThat(SpecFiles.analyze(placed.files(), null).ok()).isTrue();
    }

    @Test
    void saysWhichPartsReferencesPointToArentThere() {
        SpecFiles.Analysis a = SpecFiles.analyze(files(
                "openapi.yaml", """
                        openapi: 3.0.3
                        info: { title: A, version: '1' }
                        paths:
                          /x:
                            get:
                              responses:
                                "200": { description: ok, content: { application/json: { schema: { $ref: '#/components/schemas/Nope' } } } }
                                "404": { description: no, content: { application/json: { schema: { $ref: 'errors.yaml#/Missing' } } } }
                                "500": { description: oh, content: { application/json: { schema: { $ref: 'errors.yaml#/Error' } } } }
                        components: { schemas: { Ok: { type: object } } }
                        """,
                "errors.yaml", "Error: { type: object }\n"), null);
        assertThat(a.ok()).isFalse();
        assertThat(a.broken()).extracting(SpecFiles.Ref::target).containsExactlyInAnyOrder("openapi.yaml#/components/schemas/Nope", "errors.yaml#/Missing");
        assertThat(a.broken()).allSatisfy(r -> assertThat(r.from()).isEqualTo("openapi.yaml"));
    }
}
