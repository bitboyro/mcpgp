package ro.bitboy.mcpgp.core.diag;

import java.util.ArrayList;
import java.util.List;

/** Collects diagnostics during compilation. Not thread-safe. */
public final class Diagnostics {

    private final List<Diagnostic> items = new ArrayList<>();

    public void error(String code, String message, Location location) {
        items.add(new Diagnostic(Diagnostic.Level.ERROR, code, message, location));
    }

    public void warning(String code, String message, Location location) {
        items.add(new Diagnostic(Diagnostic.Level.WARNING, code, message, location));
    }

    public void info(String code, String message, Location location) {
        items.add(new Diagnostic(Diagnostic.Level.INFO, code, message, location));
    }

    public boolean hasErrors() {
        return items.stream().anyMatch(d -> d.level() == Diagnostic.Level.ERROR);
    }

    /** All diagnostics in a stable order: file, pointer, code, message. */
    public List<Diagnostic> all() {
        List<Diagnostic> sorted = new ArrayList<>(items);
        sorted.sort(Diagnostic.ORDER);
        return List.copyOf(sorted);
    }

    public List<Diagnostic> errors() {
        return all().stream().filter(d -> d.level() == Diagnostic.Level.ERROR).toList();
    }
}
