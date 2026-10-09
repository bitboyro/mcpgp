package ro.bitboy.mcpgp.core.diag;

/**
 * Where a diagnostic points: a file (relative to the project root, '/' separated), and optionally a
 * JSON pointer into it and a line number.
 */
public record Location(String file, String pointer, Integer line) {

    public static Location of(String file) {
        return new Location(file, null, null);
    }

    public static Location of(String file, String pointer) {
        return new Location(file, pointer, null);
    }

    public Location withPointer(String newPointer) {
        return new Location(file, newPointer, line);
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(file);
        if (line != null) {
            text.append(':').append(line);
        }
        if (pointer != null && !pointer.isEmpty()) {
            text.append('#').append(pointer);
        }
        return text.toString();
    }
}
