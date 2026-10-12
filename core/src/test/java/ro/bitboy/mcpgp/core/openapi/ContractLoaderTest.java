package ro.bitboy.mcpgp.core.openapi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Contract;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Operation;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition.ContractFiles;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition.ContractKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContractLoaderTest {

    static final Path ORDERS = Path.of("..", "examples", "platform", "orders").toAbsolutePath().normalize();

    @TempDir
    Path temp;

    private final Diagnostics diagnostics = new Diagnostics();

    private Contract load(Path root, String version, Path spec, List<Path> overlays) {
        return new ContractLoader(root, root)
                .load(new ContractKey("orders", version), new ContractFiles(spec, overlays), diagnostics)
                .orElseThrow(() -> new AssertionError(diagnostics.all()));
    }

    private static Operation op(Contract c, String key) {
        return c.operations().stream().filter(o -> o.key().equals(key)).findFirst().orElseThrow(() -> new AssertionError(key));
    }

    @Test
    void openApi31ContractLoadsWithOverlay() {
        Contract c = load(ORDERS, "2.0", ORDERS.resolve("contracts/orders-2.0.yaml"),
                List.of(ORDERS.resolve("overlays/2.0-descriptions.yaml")));

        assertThat(diagnostics.errors()).isEmpty();
        assertThat(c.format()).isEqualTo(ApiModel.Format.OPENAPI_3_1);
        assertThat(c.basePath()).isEqualTo("/api/v2");
        assertThat(c.infoVersion()).isEqualTo("2.0.3");
        assertThat(c.operations()).hasSize(14);
        assertThat(c.schemas()).containsKeys("Customer", "CustomerInput", "Order", "Problem");

        Operation create = op(c, "orders:POST:/customers");
        assertThat(create.operationId()).isEqualTo("createCustomer");
        assertThat(create.requestBody().required()).isTrue();
        assertThat(create.requestBody().content()).extracting(ApiModel.MediaType::name)
                .containsExactly("application/json", "multipart/form-data");
        assertThat(create.requestBody().content().get(1).encoding())
                .containsEntry("payload", List.of("application/json"))
                .containsEntry("document", List.of("application/pdf"));

        Operation get = op(c, "orders:GET:/customers/{id}");
        assertThat(get.parameters()).extracting(ApiModel.Parameter::name).containsExactly("id", "expand");
        assertThat(get.parameters().get(0).required()).isTrue();
        assertThat(get.parameters().get(0).style()).isEqualTo("simple");
        assertThat(get.pointer()).isEqualTo("/paths/~1customers~1{id}/get");

        Operation refund = op(c, "orders:POST:/orders/{id}/refund");
        assertThat(refund.parameters().get(1).name()).isEqualTo("notify");
        assertThat(refund.parameters().get(1).style()).isEqualTo("form");
        assertThat(refund.parameters().get(1).explode()).isTrue();

        Operation delete = op(c, "orders:DELETE:/customers/{id}");
        assertThat(delete.security()).hasSize(2);
        assertThat(delete.security().get(1).get(0).scopes()).containsExactly("orders.admin");
        assertThat(op(c, "orders:GET:/customers").security()).isNull();          // inherits the document's
        assertThat(c.security()).hasSize(3);

        assertThat(c.securitySchemes().get("basicAuth").scheme()).isEqualTo("basic");
        assertThat(c.securitySchemes().get("apiKey").parameterName()).isEqualTo("X-Api-Key");
        assertThat(c.securitySchemes().get("oauth").scopes()).containsExactly("orders.read", "orders.write", "orders.admin");

        Operation pdf = op(c, "orders:GET:/invoices/{id}/document");
        assertThat(pdf.responses()).extracting(ApiModel.Response::status).containsExactly("200", "default");
        assertThat(pdf.responses().get(0).content().get(0).name()).isEqualTo("application/pdf");

        // the overlay changed the summary
        assertThat(op(c, "orders:GET:/invoices/overdue").summary()).isEqualTo("List overdue invoices");
    }

    @Test
    void swagger20ContractLoadsIntoTheSameModel() {
        Contract c = load(ORDERS, "1.0", ORDERS.resolve("contracts/orders-1.0.json"), List.of());

        assertThat(diagnostics.errors()).isEmpty();
        assertThat(c.format()).isEqualTo(ApiModel.Format.SWAGGER_2_0);
        assertThat(c.basePath()).isEqualTo("/api/v1");
        assertThat(c.operations()).hasSize(9);

        Operation create = op(c, "orders:POST:/customers");
        assertThat(create.parameters()).isEmpty();                               // body moved to requestBody
        assertThat(create.requestBody().content()).singleElement().satisfies(m -> {
            assertThat(m.name()).isEqualTo("application/json");
            assertThat(m.schema().path("$ref").asText()).isEqualTo("#/components/schemas/CustomerInput");
        });
        Operation list = op(c, "orders:GET:/customers");
        assertThat(list.parameters().get(0).schema().path("type").asText()).isEqualTo("string");
        assertThat(list.responses().get(0).content().get(0).schema().at("/items/$ref").asText())
                .isEqualTo("#/components/schemas/Customer");
        assertThat(c.securitySchemes().get("basicAuth").type()).isEqualTo("http");
        assertThat(op(c, "orders:DELETE:/customers/{id}").security()).hasSize(1);
    }

    @Test
    void externalRefsAreInlinedInsideTheRootOnly() throws IOException {
        Path root = Files.createDirectories(temp.resolve("p"));
        Files.createDirectories(root.resolve("schemas"));
        Files.writeString(root.resolve("schemas/customer.yaml"), """
                Customer:
                  type: object
                  properties:
                    address: { $ref: '#/Address' }
                Address:
                  type: object
                  properties: { city: { type: string } }
                """);
        Path spec = Files.writeString(root.resolve("spec.yaml"), """
                openapi: 3.0.3
                info: { title: t, version: "1" }
                paths:
                  /customers:
                    get:
                      responses:
                        "200":
                          description: ok
                          content:
                            application/json:
                              schema: { $ref: 'schemas/customer.yaml#/Customer' }
                """);
        Contract c = load(root, "1", spec, List.of());
        var schema = c.operations().get(0).responses().get(0).content().get(0).schema();
        assertThat(schema.at("/properties/address/properties/city/type").asText()).isEqualTo("string");

        Files.writeString(temp.resolve("evil.yaml"), "X: { type: string }\n");
        Path escaping = Files.writeString(root.resolve("escape.yaml"), """
                openapi: 3.0.3
                info: { title: t, version: "1" }
                paths:
                  /x:
                    get:
                      responses:
                        "200": { description: ok, content: { application/json: { schema: { $ref: '../evil.yaml#/X' } } } }
                """);
        Diagnostics d = new Diagnostics();
        var result = new ContractLoader(root, root).load(new ContractKey("orders", "1"), new ContractFiles(escaping, List.of()), d);
        assertThat(result).isEmpty();
        assertThat(d.errors()).singleElement().satisfies(e -> assertThat(e.code()).isEqualTo("PATH_OUTSIDE_ROOT"));
    }

    @Test
    void recursiveSchemasAcrossFilesBecomeSharedSchemas() throws IOException {
        Path root = Files.createDirectories(temp.resolve("r"));
        Files.createDirectories(root.resolve("schemas/criteria"));
        Files.createDirectories(root.resolve("schemas/certificates"));
        // a tree through two files, as a oneOf
        Files.writeString(root.resolve("schemas/criteria/criteria-bean.json"), """
                { "oneOf": [ { "$ref": "metadata-condition.json" }, { "$ref": "conjunctor.json" } ] }
                """);
        Files.writeString(root.resolve("schemas/criteria/metadata-condition.json"), """
                { "type": "object", "properties": { "key": { "type": "string" } } }
                """);
        Files.writeString(root.resolve("schemas/criteria/conjunctor.json"), """
                { "type": "object", "properties": { "children": { "type": "array", "items": { "$ref": "criteria-bean.json" } } } }
                """);
        // a chain: the file refers to itself, by name and by "#"
        Files.writeString(root.resolve("schemas/certificates/complete-tls-certificate.json"), """
                { "type": "object", "properties": { "parent": { "$ref": "complete-tls-certificate.json" },
                  "issuer": { "$ref": "#" } } }
                """);
        Path spec = Files.writeString(root.resolve("spec.yaml"), """
                openapi: 3.0.3
                info: { title: t, version: "1" }
                paths:
                  /search:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: { $ref: '#/components/schemas/Criteria' }
                      responses:
                        "200":
                          description: ok
                          content:
                            application/json:
                              schema: { $ref: 'schemas/certificates/complete-tls-certificate.json' }
                components:
                  schemas:
                    Criteria: { $ref: 'schemas/criteria/criteria-bean.json' }
                """);
        Contract c = load(root, "1", spec, List.of());
        assertThat(diagnostics.errors()).isEmpty();

        // the entry that points to the file keeps its name; the other gets its file's
        assertThat(c.schemas().get("Criteria").at("/oneOf/1/properties/children/items/$ref").asText())
                .isEqualTo("#/components/schemas/Criteria");
        assertThat(c.schemas().get("Criteria").at("/oneOf/0/properties/key/type").asText()).isEqualTo("string");
        var certificate = c.schemas().get("CompleteTlsCertificate");
        assertThat(certificate.at("/properties/parent/$ref").asText()).isEqualTo("#/components/schemas/CompleteTlsCertificate");
        assertThat(certificate.at("/properties/issuer/$ref").asText()).isEqualTo("#/components/schemas/CompleteTlsCertificate");
        assertThat(op(c, "orders:POST:/search").responses().get(0).content().get(0).schema().path("$ref").asText())
                .isEqualTo("#/components/schemas/CompleteTlsCertificate");
        // and the tool's schema is self-contained, with the recursive part in $defs
        var shaped = new ro.bitboy.mcpgp.core.schema.SchemaShaper(c.schemas(), c.format())
                .selfContained(op(c, "orders:POST:/search").requestBody().content().get(0).schema(), ro.bitboy.mcpgp.core.schema.SchemaShaper.Direction.INPUT);
        assertThat(shaped.toString()).contains("#/$defs/Criteria").doesNotContain("components");
        assertThat(shaped.has("$defs")).isTrue();
    }

    @Test
    void recursiveSchemasAcrossFilesInSwagger20() throws IOException {
        Path root = Files.createDirectories(temp.resolve("s"));
        Files.writeString(root.resolve("node.yaml"), """
                type: object
                properties:
                  next: { $ref: 'node.yaml' }
                """);
        Path spec = Files.writeString(root.resolve("spec.yaml"), """
                swagger: "2.0"
                info: { title: t, version: "1" }
                paths:
                  /nodes:
                    get:
                      responses:
                        "200": { description: ok, schema: { $ref: 'node.yaml' } }
                """);
        Contract c = load(root, "1", spec, List.of());
        assertThat(c.schemas().get("Node").at("/properties/next/$ref").asText()).isEqualTo("#/components/schemas/Node");
        assertThat(op(c, "orders:GET:/nodes").responses().get(0).content().get(0).schema().path("$ref").asText())
                .isEqualTo("#/components/schemas/Node");
    }

    @Test
    void openApi32IsNotSupportedYet() throws IOException {
        Path spec = Files.writeString(temp.resolve("s.yaml"), "openapi: 3.2.0\ninfo: { title: t, version: '1' }\npaths: {}\n");
        var result = new ContractLoader(temp, temp).load(new ContractKey("a", "1"), new ContractFiles(spec, List.of()), diagnostics);
        assertThat(result).isEmpty();
        assertThat(diagnostics.errors()).singleElement().satisfies(e -> assertThat(e.code()).isEqualTo("SPEC_VERSION_UNSUPPORTED"));
    }

    @Test
    void ignoredFeaturesAreReportedOnce() throws IOException {
        Path spec = Files.writeString(temp.resolve("s.yaml"), """
                openapi: 3.1.0
                info: { title: t, version: "1" }
                webhooks: { newOrder: {} }
                paths:
                  /a: { get: { callbacks: { x: {} }, responses: { "200": { description: ok } } } }
                  /b: { get: { callbacks: { y: {} }, responses: { "200": { description: ok } } } }
                """);
        load(temp, "1", spec, List.of());
        assertThat(diagnostics.all()).filteredOn(d -> d.level() == Diagnostic.Level.INFO)
                .extracting(Diagnostic::message)
                .containsExactlyInAnyOrder("Not supported, ignored: webhooks", "Not supported, ignored: callbacks");
    }

    @Test
    void overlayCanRemoveOperations() throws IOException {
        Path spec = Files.writeString(temp.resolve("s.yaml"), """
                openapi: 3.1.0
                info: { title: t, version: "1" }
                paths:
                  /a: { get: { responses: { "200": { description: ok } } } }
                  /b: { get: { responses: { "200": { description: ok } } } }
                """);
        Path overlay = Files.writeString(temp.resolve("o.yaml"), """
                overlay: 1.0.0
                info: { title: o, version: "1" }
                actions:
                  - { target: "$.paths['/b']", remove: true }
                  - { target: "$.paths['/nothing']", remove: true }
                """);
        Contract c = load(temp, "1", spec, List.of(overlay));
        assertThat(c.operations()).extracting(Operation::path).containsExactly("/a");
        assertThat(diagnostics.all()).anySatisfy(d -> assertThat(d.code()).isEqualTo("OVERLAY_TARGET_MATCHES_NOTHING"));
    }
}
