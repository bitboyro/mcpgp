package ro.bitboy.mcpgp.runtime;

import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What the server starts with: the manifest (embedded, or {@code --manifest} for development), the
 * loaded runtime config, and what it was loaded from (to load it again on reload).
 *
 * <p>Arguments: {@code --config <file>}, {@code --manifest <file>}, and config overrides as
 * {@code --<dotted.key> <value>} or {@code --<dotted.key>=<value>} (runtime-config §1).
 */
public record Startup(Manifest manifest, RuntimeConfig config, String[] args, Map<String, String> env, Path workingDir) {

    public static Startup load(String[] args, Map<String, String> env, Path workingDir) {
        Map<String, String> flags = flags(args);
        Optional<Path> configFile = Optional.ofNullable(flags.remove("config")).map(Path::of);
        Manifest manifest = manifest(flags.remove("manifest"));
        RuntimeConfig config = new ConfigLoader(new Schemas())
                .load(manifest, new ConfigLoader.Options(configFile, workingDir, env, flags));
        return new Startup(manifest, config, args.clone(), Map.copyOf(env), workingDir);
    }

    /** The embedded manifest, or the {@code --manifest} file in development. */
    static Manifest manifest(String file) {
        return file == null ? Manifest.embedded() : Manifest.file(Path.of(file));
    }

    static Map<String, String> flags(String[] args) {
        Map<String, String> flags = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") || arg.length() == 2) {
                throw new IllegalArgumentException("Unexpected argument '" + arg + "'; options look like --config <file> or --server.port 8080");
            }
            String key = arg.substring(2);
            int eq = key.indexOf('=');
            if (eq > 0) {
                flags.put(key.substring(0, eq), key.substring(eq + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                flags.put(key, args[++i]);
            } else {
                flags.put(key, "true");
            }
        }
        return flags;
    }
}
