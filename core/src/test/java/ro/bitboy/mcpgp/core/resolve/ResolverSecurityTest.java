package ro.bitboy.mcpgp.core.resolve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Path and reference rules from definition-format §7 and security.md. */
class ResolverSecurityTest {

    @TempDir
    Path temp;

    private final DefinitionResolver resolver = new DefinitionResolver(new Schemas());

    private Path project() throws IOException {
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.createDirectories(project.resolve("contracts"));
        Files.writeString(project.resolve("contracts/orders.yaml"), "openapi: 3.1.0\n");
        return project;
    }

    private Path definition(Path project, String contractSpec, String extra) throws IOException {
        return Files.writeString(project.resolve("d.mcpgp.yaml"), """
                apiVersion: mcpgp/v1
                kind: McpServer
                metadata: { name: test-mcp }
                apis:
                  - id: orders
                    contracts: [{ version: "1.0", spec: "%s" }]
                %s""".formatted(contractSpec, extra));
    }

    private String codeOf(Path file) {
        var result = resolver.resolve(file, Workspace.none());
        assertThat(result.definition()).isEmpty();
        return result.diagnostics().stream().filter(d -> d.level().name().equals("ERROR")).findFirst().orElseThrow().code();
    }

    @Test
    void validProjectResolves() throws IOException {
        Path project = project();
        var result = resolver.resolve(definition(project, "contracts/orders.yaml", ""), Workspace.none());
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.definition()).isPresent();
    }

    @Test
    void relativePathLeavingTheRootIsRejected() throws IOException {
        Path project = project();
        Files.writeString(temp.resolve("outside.yaml"), "openapi: 3.1.0\n");
        assertThat(codeOf(definition(project, "../outside.yaml", ""))).isEqualTo("PATH_OUTSIDE_ROOT");
    }

    @Test
    void absolutePathIsRejected() throws IOException {
        Path project = project();
        assertThat(codeOf(definition(project, project.resolve("contracts/orders.yaml").toString(), ""))).isEqualTo("PATH_OUTSIDE_ROOT");
    }

    @Test
    void symlinkLeavingTheRootIsRejected() throws IOException {
        Path project = project();
        Path outside = Files.writeString(temp.resolve("secret.yaml"), "openapi: 3.1.0\n");
        Files.createSymbolicLink(project.resolve("contracts/link.yaml"), outside);
        assertThat(codeOf(definition(project, "contracts/link.yaml", ""))).isEqualTo("PATH_OUTSIDE_ROOT");
    }

    @Test
    void remoteReferenceIsRejected() throws IOException {
        Path project = project();
        assertThat(codeOf(definition(project, "https://evil.example/spec.yaml", ""))).isEqualTo("REMOTE_REFERENCE_NOT_ALLOWED");
    }

    @Test
    void missingFileIsReported() throws IOException {
        Path project = project();
        assertThat(codeOf(definition(project, "contracts/nope.yaml", ""))).isEqualTo("FILE_NOT_FOUND");
    }

    @Test
    void includeOutsideTheRootIsRejected() throws IOException {
        Path project = project();
        assertThat(codeOf(definition(project, "contracts/orders.yaml", "overrides:\n  include: ../*.yaml\n")))
                .isEqualTo("PATH_OUTSIDE_ROOT");
    }

    @Test
    void referenceCycleIsReported() throws IOException {
        Path project = project();
        Files.writeString(project.resolve("a.yaml"), "$ref: b.yaml\n");
        Files.writeString(project.resolve("b.yaml"), "$ref: a.yaml\n");
        assertThat(codeOf(definition(project, "contracts/orders.yaml", "packs:\n  audit: { $ref: a.yaml }\n")))
                .isEqualTo("REFERENCE_CYCLE");
    }

    @Test
    void schemaErrorsPointIntoTheFile() throws IOException {
        Path project = project();
        Path file = definition(project, "contracts/orders.yaml", "exposure: { mode: everything }\n");
        var result = resolver.resolve(file, Workspace.none());
        assertThat(result.diagnostics()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo("DEFINITION_INVALID");
            assertThat(d.location().pointer()).isEqualTo("/exposure/mode");
        });
    }
}
