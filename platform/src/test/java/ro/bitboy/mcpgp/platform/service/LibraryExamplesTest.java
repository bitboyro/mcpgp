package ro.bitboy.mcpgp.platform.service;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.platform.store.FileStore;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.schemas.SchemaKind;
import ro.bitboy.mcpgp.schemas.Schemas;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

class LibraryExamplesTest {
    @TempDir Path dir;
    @Test void seedsEveryKindAndFourTemplatesWithoutDuplicatingOrOverwriting() {
        var store = new FileStore(dir);
        LibraryExamples.ensure(store);
        assertThat(store.library().stream().map(Model.LibraryItem::kind).collect(Collectors.toSet())).isEqualTo(Library.KINDS);
        assertThat(store.library().stream().filter(i -> i.kind().equals("templates")).map(Model.LibraryItem::name))
                .containsExactlyInAnyOrder("bare", "default", "basic", "enterprise");
        var original = store.libraryItem("naming", "example-readable-names").orElseThrow();
        store.saveLibraryItem(new Model.LibraryItem(original.kind(), original.name(), "My naming rules", original.created(), original.updated(), original.versions(), original.revision()), original.revision());
        LibraryExamples.ensure(store);
        assertThat(store.library()).hasSize(9);
        assertThat(store.libraryItem(original.kind(), original.name()).orElseThrow().title()).isEqualTo("My naming rules");
        assertThat(new ConfigurationTemplates(store).all()).hasSize(4);
    }
    @Test void examplesValidateAndEveryTemplateCompilesWithTheExampleContract() throws Exception {
        var yaml = new YAMLMapper(); var schemas = new Schemas();
        for (var e : LibraryExamples.examples()) {
            var kind = switch(e.kind()) {
                case "naming" -> SchemaKind.NAMING_POLICY;
                case "rules" -> SchemaKind.RULES;
                case "notices" -> SchemaKind.NOTICES;
                default -> null;
            };
            if(kind != null) assertThat(schemas.validate(kind, yaml.readTree(e.content()))).as(e.name()).isEmpty();
            if(e.kind().equals("templates")) assertThat(ConfigurationTemplates.validate(e.content())).as(e.name()).isEmpty();
        }
        var contract = LibraryExamples.examples().stream().filter(e -> e.kind().equals("contracts")).findFirst().orElseThrow();
        Files.writeString(dir.resolve("api.yaml"), contract.content());
        String definition = """
                apiVersion: mcpgp/v1
                kind: McpServer
                metadata: {name: example-tasks}
                apis:
                  - id: tasks
                    contracts: [{version: '1.0.0', spec: api.yaml, default: true}]
                    upstreamAuth: {supported: [bearer]}
                """;
        for (var t : new ConfigurationTemplates(null).builtIns()) {
            Path root = dir.resolve("example.mcpgp.yaml");
            Files.write(root, new ConfigurationTemplates(null).apply(definition.getBytes(java.nio.charset.StandardCharsets.UTF_8), t.id()));
            var result = new ro.bitboy.mcpgp.core.Compiler(schemas).compile(root, ro.bitboy.mcpgp.core.Compiler.Options.defaults());
            assertThat(result.ok()).as(t.title()+": "+result.diagnostics()).isTrue();
        }
    }
}
