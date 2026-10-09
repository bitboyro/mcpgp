package ro.bitboy.mcpgp.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.core.diag.Codes;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Contract;
import ro.bitboy.mcpgp.core.manifest.ManifestWriter;
import ro.bitboy.mcpgp.core.openapi.ContractLoader;
import ro.bitboy.mcpgp.core.resolve.DefinitionResolver;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition.ContractKey;
import ro.bitboy.mcpgp.core.resolve.Workspace;
import ro.bitboy.mcpgp.core.tools.NamesLock;
import ro.bitboy.mcpgp.core.tools.ToolPlan;
import ro.bitboy.mcpgp.core.tools.ToolPlanner;
import ro.bitboy.mcpgp.schemas.SchemaKind;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The compiler's entry point: definition project → tool plans → manifest. */
public final class Compiler {

    /** The compiler's own version, recorded in manifests. */
    public static final String VERSION = "0.1.0-SNAPSHOT";

    /**
     * @param frozenLock fail instead of warning when a tool name would differ from the lock file
     * @param build      build inputs that don't come from the definition (time, version, source)
     */
    public record Options(Workspace workspace, boolean frozenLock, ManifestWriter.Build build) {
        public Options(Workspace workspace, boolean frozenLock) {
            this(workspace, frozenLock, new ManifestWriter.Build(Instant.EPOCH, VERSION, null, null, null));
        }

        public static Options defaults() {
            return new Options(Workspace.none(), false);
        }
    }

    /**
     * What the compiler produced; {@code definition}, {@code tools} and {@code manifest} are empty when
     * there were errors.
     */
    public record Result(Optional<ResolvedDefinition> definition, Map<ContractKey, Contract> contracts,
                         List<ToolPlan> tools, Optional<ObjectNode> manifest, List<Diagnostic> diagnostics) {
        public boolean ok() {
            return definition.isPresent() && diagnostics.stream().noneMatch(d -> d.level() == Diagnostic.Level.ERROR);
        }
    }

    private final Schemas schemas;

    public Compiler(Schemas schemas) {
        this.schemas = schemas;
    }

    public Result compile(Path definitionFile, Options options) {
        DefinitionResolver.Result resolved = new DefinitionResolver(schemas).resolve(definitionFile, options.workspace());
        Diagnostics diagnostics = new Diagnostics();
        List<Diagnostic> collected = new ArrayList<>(resolved.diagnostics());
        if (resolved.definition().isEmpty()) {
            return new Result(Optional.empty(), Map.of(), List.of(), Optional.empty(), sorted(collected));
        }
        ResolvedDefinition definition = resolved.definition().get();

        ContractLoader loader = new ContractLoader(definition.root(), definition.root());
        Map<ContractKey, Contract> contracts = new LinkedHashMap<>();
        definition.contracts().forEach((key, files) ->
                loader.load(key, files, diagnostics).ifPresent(contract -> contracts.put(key, contract)));

        List<ToolPlan> tools = List.of();
        if (!diagnostics.hasErrors()) {
            NamesLock lock = NamesLock.load(definition.root(), schemas, diagnostics);
            tools = new ToolPlanner(diagnostics, options.frozenLock()).plan(definition, contracts, lock);
        }
        ObjectNode manifest = null;
        if (!diagnostics.hasErrors() && collected.stream().noneMatch(d -> d.level() == Diagnostic.Level.ERROR)) {
            ManifestWriter writer = new ManifestWriter(diagnostics);
            manifest = writer.write(definition, contracts, tools, options.build());
            // the manifest records every warning and info, including the ones the writer reported
            List<Diagnostic> all = new ArrayList<>(collected);
            all.addAll(diagnostics.all());
            manifest = writer.withDiagnostics(manifest, sorted(all));
            for (Schemas.Problem problem : schemas.problems(SchemaKind.MANIFEST, manifest)) {
                diagnostics.error(Codes.MANIFEST_INVALID, problem.toString(), null);
            }
        }
        collected.addAll(diagnostics.all());
        boolean failed = collected.stream().anyMatch(d -> d.level() == Diagnostic.Level.ERROR);
        return new Result(failed ? Optional.empty() : Optional.of(definition), Map.copyOf(contracts),
                failed ? List.of() : tools, failed ? Optional.empty() : Optional.ofNullable(manifest), sorted(collected));
    }

    private static List<Diagnostic> sorted(List<Diagnostic> list) {
        Diagnostics d = new Diagnostics();
        list.forEach(x -> {
            switch (x.level()) {
                case ERROR -> d.error(x.code(), x.message(), x.location());
                case WARNING -> d.warning(x.code(), x.message(), x.location());
                case INFO -> d.info(x.code(), x.message(), x.location());
            }
        });
        return d.all();
    }
}
