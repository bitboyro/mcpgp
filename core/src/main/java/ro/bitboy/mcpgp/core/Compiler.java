package ro.bitboy.mcpgp.core;

import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Contract;
import ro.bitboy.mcpgp.core.openapi.ContractLoader;
import ro.bitboy.mcpgp.core.resolve.DefinitionResolver;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition.ContractKey;
import ro.bitboy.mcpgp.core.resolve.Workspace;
import ro.bitboy.mcpgp.core.tools.NamesLock;
import ro.bitboy.mcpgp.core.tools.ToolPlan;
import ro.bitboy.mcpgp.core.tools.ToolPlanner;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The compiler's entry point: definition project → tool plans (and, in later stages, the manifest). */
public final class Compiler {

    /** @param frozenLock fail instead of warning when a tool name would differ from the lock file */
    public record Options(Workspace workspace, boolean frozenLock) {
        public static Options defaults() {
            return new Options(Workspace.none(), false);
        }
    }

    /** What the compiler produced; {@code definition} and {@code tools} are empty when there were errors. */
    public record Result(Optional<ResolvedDefinition> definition, Map<ContractKey, Contract> contracts,
                         List<ToolPlan> tools, List<Diagnostic> diagnostics) {
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
            return new Result(Optional.empty(), Map.of(), List.of(), sorted(collected));
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
        collected.addAll(diagnostics.all());
        boolean failed = collected.stream().anyMatch(d -> d.level() == Diagnostic.Level.ERROR);
        return new Result(failed ? Optional.empty() : Optional.of(definition), Map.copyOf(contracts),
                failed ? List.of() : tools, sorted(collected));
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
