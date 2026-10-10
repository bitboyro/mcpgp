package ro.bitboy.mcpgp.runtime.manifest;

/** The manifest is missing, unreadable or of an unsupported version; the server can't start. */
public final class ManifestException extends RuntimeException {
    public ManifestException(String message) {
        super(message);
    }
}
