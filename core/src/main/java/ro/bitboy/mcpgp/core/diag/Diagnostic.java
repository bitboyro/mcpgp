package ro.bitboy.mcpgp.core.diag;

import java.util.Comparator;

/** One error, warning or info reported by the compiler. Codes are stable (see definition-format §9). */
public record Diagnostic(Level level, String code, String message, Location location) {

    public enum Level { ERROR, WARNING, INFO }

    static final Comparator<Diagnostic> ORDER = Comparator
            .comparing((Diagnostic d) -> d.location() == null ? "" : d.location().file())
            .thenComparing(d -> d.location() == null || d.location().pointer() == null ? "" : d.location().pointer())
            .thenComparing(Diagnostic::code)
            .thenComparing(Diagnostic::message);

    @Override
    public String toString() {
        return level.name().toLowerCase() + " " + code + (location == null ? "" : " at " + location) + ": " + message;
    }
}
