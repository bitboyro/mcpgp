package ro.bitboy.mcpgp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code client add <client>} (packaging.md §5): merges only this server's entry into an MCP client's
 * JSON config, after a backup; {@code --dry-run} shows the change, {@code --print} only prints the
 * entry. Entries never contain secrets: the server reads its own config and {@code .env}.
 */
final class ClientRegistration {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Where a client keeps its MCP servers: file, top-level key, and whether it takes stdio / HTTP entries. */
    record Client(String id, Path file, String key, boolean stdio, boolean http, String note) {
    }

    private final Manifest manifest;
    private final RuntimeConfig config;
    private final Path workingDir;
    private final Map<String, String> env;
    private final PrintStream out;
    /** The jar entries point to; null for the running one. */
    private final Path jarOverride;

    ClientRegistration(Manifest manifest, RuntimeConfig config, Path workingDir, Map<String, String> env, PrintStream out, Path jar) {
        this.jarOverride = jar;
        this.manifest = manifest;
        this.config = config;
        this.workingDir = workingDir;
        this.env = env;
        this.out = out;
    }

    /**
     * @param fileOverride a client config file to use instead of the client's usual one
     * @param name         the entry name; defaults to the server name without {@code -mcp}
     */
    int add(String clientId, Path fileOverride, String name, boolean print, boolean dryRun) {
        Client client;
        try {
            client = client(clientId);
        } catch (IllegalArgumentException e) {
            out.println("✗ " + e.getMessage());
            return 2;
        }
        boolean http = config.server().transport().equals("http");
        if (http && !client.http() || !http && !client.stdio()) {
            out.println("✗ " + client.id() + " " + client.note());
            return 1;
        }
        ObjectNode entry;
        try {
            entry = http ? httpEntry(client) : stdioEntry(client, print);
        } catch (IllegalStateException e) {
            out.println("✗ " + e.getMessage());
            return 1;
        }
        String entryName = name != null ? name : manifest.serverName().replaceAll("-mcp$", "");
        if (print) {
            out.println(pretty(JSON.createObjectNode().set(entryName, entry)));
            return 0;
        }
        Path file = fileOverride != null ? fileOverride : client.file();
        try {
            ObjectNode root = Files.exists(file) ? readObject(file) : JSON.createObjectNode();
            ObjectNode servers = root.has(client.key()) && root.get(client.key()).isObject()
                    ? (ObjectNode) root.get(client.key()) : root.putObject(client.key());
            JsonNode previous = servers.get(entryName);
            if (entry.equals(previous)) {
                out.println("✓ " + file + " already has this entry for " + entryName);
                return 0;
            }
            out.println((previous == null ? "Adds" : "Replaces") + " \"" + entryName + "\" in " + file + ":");
            if (previous != null) {
                out.println("- " + pretty(previous).replace("\n", "\n- "));
            }
            out.println("+ " + pretty(entry).replace("\n", "\n+ "));
            if (dryRun) {
                out.println("(dry run: nothing written)");
                return 0;
            }
            servers.set(entryName, entry);
            if (Files.exists(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.createDirectories(file.toAbsolutePath().getParent());
            }
            Files.writeString(file, pretty(root) + "\n");
            out.println("✓ written" + (Files.exists(file.resolveSibling(file.getFileName() + ".bak")) ? " (previous version: "
                    + file.getFileName() + ".bak)" : "") + "; restart " + client.id() + " to load it");
            return 0;
        } catch (IOException e) {
            out.println("✗ " + file + ": " + e.getMessage());
            return 1;
        }
    }

    private Client client(String id) {
        Path home = Path.of(System.getProperty("user.home"));
        return switch (id) {
            case "claude-code" -> new Client(id, workingDir.resolve(".mcp.json"), "mcpServers", true, true, "");
            case "claude-desktop" -> new Client(id, claudeDesktop(home), "mcpServers", true, false,
                    "takes remote servers in the app's connector settings, not in its config file; add " + url() + " there");
            case "cursor" -> new Client(id, home.resolve(".cursor/mcp.json"), "mcpServers", true, true, "");
            case "vscode" -> new Client(id, workingDir.resolve(".vscode/mcp.json"), "servers", true, true, "");
            default -> throw new IllegalArgumentException("Unknown client '" + id
                    + "'; use claude-code, claude-desktop, cursor or vscode, or --print for others");
        };
    }

    private Path claudeDesktop(Path home) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return Path.of(env.getOrDefault("APPDATA", home.resolve("AppData/Roaming").toString()), "Claude", "claude_desktop_config.json");
        }
        if (os.contains("mac")) {
            return home.resolve("Library/Application Support/Claude/claude_desktop_config.json");
        }
        return home.resolve(".config/Claude/claude_desktop_config.json");
    }

    private ObjectNode stdioEntry(Client client, boolean print) {
        ObjectNode entry = JSON.createObjectNode();
        if (client.id().equals("vscode") || client.id().equals("claude-code")) {
            entry.put("type", "stdio");
        }
        entry.put("command", Path.of(System.getProperty("java.home"), "bin", "java").toString());
        var args = entry.putArray("args");
        args.add("-jar").add(jarPath(print));
        config.source().ifPresent(source -> args.add("--config").add(source.toAbsolutePath().toString()));
        return entry;
    }

    private ObjectNode httpEntry(Client client) {
        ObjectNode entry = JSON.createObjectNode();
        if (!client.id().equals("cursor")) {
            entry.put("type", "http");
        }
        entry.put("url", url());
        return entry;
    }

    private String url() {
        RuntimeConfig.Server server = config.server();
        String base = server.publicUrl() != null ? server.publicUrl().replaceAll("/$", "")
                : "http://" + (server.host().equals("0.0.0.0") ? "127.0.0.1" : server.host()) + ":" + server.port();
        return base + server.basePath();
    }

    /** The jar this server runs from. */
    private static Path jar() {
        Path found = locateJar(System.getProperty("java.class.path"), codeSource());
        if (found == null) {
            throw new IllegalStateException("This server isn't running from a jar; use --print and adjust the path");
        }
        return found;
    }

    private static String codeSource() {
        try {
            var source = ClientRegistration.class.getProtectionDomain().getCodeSource();
            return source == null ? null : source.getLocation().toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The jar a server was started from: {@code java -jar x.jar} makes it the whole class path; otherwise
     * the code source, which in a Spring Boot jar looks like {@code jar:nested:/dir/x.jar/!BOOT-INF/classes/!/}
     * or {@code jar:file:/dir/x.jar!/BOOT-INF/classes!/}.
     */
    static Path locateJar(String classPath, String codeSource) {
        if (classPath != null && classPath.endsWith(".jar") && !classPath.contains(java.io.File.pathSeparator)) {
            Path path = Path.of(classPath).toAbsolutePath();
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        if (codeSource != null) {
            String text = codeSource.replaceFirst("^jar:", "").replaceFirst("^nested:", "").replaceFirst("^file:", "");
            int end = text.indexOf(".jar");
            if (end > 0) {
                try {
                    Path path = Path.of(java.net.URLDecoder.decode(text.substring(0, end + 4), java.nio.charset.StandardCharsets.UTF_8));
                    if (Files.isRegularFile(path)) {
                        return path;
                    }
                } catch (RuntimeException e) {
                    // not a path: the jar can't be located
                }
            }
        }
        return null;
    }

    /** With {@code --print} a jar that can't be located is a placeholder to replace, not an error. */
    private String jarPath(boolean print) {
        if (jarOverride != null) {
            return jarOverride.toString();
        }
        try {
            return jar().toString();
        } catch (IllegalStateException e) {
            if (!print) {
                throw e;
            }
            out.println("! the server's jar couldn't be located: replace <path to the server jar> below");
            return "<path to the server jar>";
        }
    }

    private static ObjectNode readObject(Path file) throws IOException {
        JsonNode node;
        try {
            node = JSON.readTree(file.toFile());
        } catch (IOException e) {
            throw new IOException("not valid JSON (comments aren't supported); fix it or use --print");
        }
        if (node == null || node.isMissingNode()) {
            return JSON.createObjectNode();
        }
        if (!node.isObject()) {
            throw new IOException("expected a JSON object");
        }
        return (ObjectNode) node;
    }

    private static String pretty(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<String> clients() {
        return List.of("claude-code", "claude-desktop", "cursor", "vscode");
    }
}
