package ro.bitboy.mcpgp.runtime;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.runtime.config.BaseUrl;
import ro.bitboy.mcpgp.runtime.config.ConfigException;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.config.TlsBuilder;
import ro.bitboy.mcpgp.runtime.diag.Probe;
import ro.bitboy.mcpgp.runtime.manifest.ManifestException;
import ro.bitboy.mcpgp.runtime.mcp.ToolError;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Customer commands of a delivered server (runtime-config §8), run without starting the server:
 * {@code doctor [--quick]}, {@code instance list}, {@code instance test <id>},
 * {@code instance trust <id> [--base64] [--yes]}. Exit codes: 0 ok, 1 a check failed, 2 usage.
 */
public final class ServerCommands {

    static final Set<String> COMMANDS = Set.of("doctor", "instance", "init", "client", "reload");

    private final Map<String, String> env;
    private final Path workingDir;
    private final BufferedReader in;
    private final PrintStream out;
    private final boolean console;
    /** The jar client entries point to; null to use the running jar. */
    Path jar;

    public ServerCommands(Map<String, String> env, Path workingDir, InputStream in, PrintStream out) {
        this.env = env;
        this.workingDir = workingDir;
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
        this.console = in == System.in;
    }

    public int run(String[] args) {
        List<String> words = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        boolean quick = false;
        boolean base64 = false;
        boolean yes = false;
        boolean print = false;
        boolean dryRun = false;
        String api = null;
        String name = null;
        Path clientFile = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--quick" -> quick = true;
                case "--base64" -> base64 = true;
                case "--yes" -> yes = true;
                case "--print" -> print = true;
                case "--dry-run" -> dryRun = true;
                case "--api" -> api = i + 1 < args.length ? args[++i] : null;
                case "--name" -> name = i + 1 < args.length ? args[++i] : null;
                case "--file" -> clientFile = i + 1 < args.length ? Path.of(args[++i]) : null;
                default -> {
                    if (args[i].startsWith("--")) {
                        rest.add(args[i]);
                        if (!args[i].contains("=") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                            rest.add(args[++i]);
                        }
                    } else {
                        words.add(args[i]);
                    }
                }
            }
        }
        if (!words.isEmpty() && words.get(0).equals("init")) {
            return init(rest);
        }
        Startup startup;
        try {
            startup = Startup.load(rest.toArray(String[]::new), env, workingDir);
        } catch (ConfigException e) {
            out.println("✗ config");
            e.problems().forEach(p -> out.println("    " + p));
            return 1;
        } catch (ManifestException | IllegalArgumentException e) {
            out.println("✗ " + e.getMessage());
            return 1;
        }
        String command = String.join(" ", words.subList(0, Math.min(2, words.size())));
        try {
            return switch (command) {
                case "doctor" -> doctor(startup, quick);
                case "reload" -> reload(startup.config());
                case "instance list" -> list(startup.config());
                case "instance test" -> words.size() == 3 ? test(instance(startup.config(), words.get(2))) : usage();
                case "instance trust" -> words.size() == 3 ? trust(startup.config(), instance(startup.config(), words.get(2)), base64, yes) : usage();
                case "instance add" -> words.size() == 3 ? setup(startup.manifest(), rest).add(startup.config(), words.get(2), api) : usage();
                case "instance remove" -> words.size() == 3 ? setup(startup.manifest(), rest).remove(startup.config(), words.get(2), api) : usage();
                case "client add" -> words.size() == 3
                        ? new ClientRegistration(startup.manifest(), startup.config(), workingDir, env, out, jar).add(words.get(2), clientFile, name, print, dryRun)
                        : usage();
                default -> usage();
            };
        } catch (IllegalArgumentException e) {
            out.println("✗ " + e.getMessage());
            return 1;
        } catch (IOException e) {
            out.println("✗ " + e.getMessage());
            return 1;
        }
    }

    private int init(List<String> rest) {
        try {
            Map<String, String> flags = Startup.flags(rest.toArray(String[]::new));
            String config = flags.get("config");
            return setup(Startup.manifest(flags.get("manifest")), rest).init(config == null ? null : Path.of(config));
        } catch (ManifestException | IllegalArgumentException e) {
            out.println("✗ " + e.getMessage());
            return 1;
        } catch (IOException e) {
            out.println("✗ " + e.getMessage());
            return 1;
        }
    }

    /** Setup commands; they validate their result by loading it with the same options (manifest, flags). */
    private SetupCommands setup(ro.bitboy.mcpgp.runtime.manifest.Manifest manifest, List<String> rest) {
        return new SetupCommands(manifest, env, workingDir, in, out, file -> {
            List<String> options = new ArrayList<>();
            for (int i = 0; i < rest.size(); i++) {
                if (rest.get(i).equals("--config")) {
                    i++;
                } else if (!rest.get(i).startsWith("--config=")) {
                    options.add(rest.get(i));
                }
            }
            options.add("--config");
            options.add(file.toString());
            return Startup.load(options.toArray(String[]::new), env, workingDir).config();
        }, console);
    }

    private int usage() {
        out.println("Usage: init | doctor [--quick] | reload | instance list | instance test|add|remove|trust <id> | client add <client>");
        out.println("       instance trust: [--base64] [--yes]   instance add/remove: [--api <id>]");
        out.println("       client add claude-code|claude-desktop|cursor|vscode [--print] [--dry-run] [--file <path>] [--name <entry>]");
        out.println("       (plus --config <file> and --<key> <value> as when starting the server)");
        return 2;
    }

    // ---------------------------------------------------------------------- doctor

    private int doctor(Startup startup, boolean quick) {
        RuntimeConfig config = startup.config();
        boolean ok = true;
        out.println("✓ config" + config.source().map(p -> " (" + p + ")").orElse(" (environment only)"));
        config.warnings().forEach(w -> out.println("! " + w));
        if (config.inlineSecrets()) {
            out.println("✗ allowInlineSecrets is on: move secrets to references (${env:…}, ${file:…})");
            ok = false;
        }
        boolean http = config.server().transport().equals("http");
        if (quick) {
            if (http) {
                ok &= live(config);
            }
            return ok ? 0 : 1;
        }
        for (RuntimeConfig.Instance instance : config.allInstances()) {
            ok &= report(instance, Probe.connectivity(instance));
        }
        if (http) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.bind(new InetSocketAddress(config.server().host(), config.server().port()));
                out.println("✓ port " + config.server().host() + ":" + config.server().port() + " is free");
            } catch (IOException e) {
                out.println("! port " + config.server().host() + ":" + config.server().port() + " is in use (is the server running?)");
            }
            if (config.server().publicUrl() != null) {
                try {
                    BaseUrl.parse(config.server().publicUrl());
                    out.println("✓ public URL " + config.server().publicUrl());
                } catch (IllegalArgumentException e) {
                    out.println("✗ public URL: " + e.getMessage());
                    ok = false;
                }
            }
        }
        out.println(ok ? "All checks passed." : "Some checks failed.");
        return ok ? 0 : 1;
    }

    /** Leaves a reload request for the running server (runtime-config §4.6); works on every platform. */
    private int reload(RuntimeConfig config) throws IOException {
        Files.createDirectories(config.storageRoot());
        Files.writeString(config.storageRoot().resolve(Reloader.REQUEST), java.time.Instant.now().toString());
        out.println("✓ reload requested; a running server picks it up within a second (its log says whether the new config was applied)");
        return 0;
    }

    private boolean live(RuntimeConfig config) {
        Integer management = config.server().managementPort();
        String host = config.server().host().equals("0.0.0.0") ? "127.0.0.1" : config.server().host();
        URI uri = URI.create("http://" + host + ":" + (management != null ? management : config.server().port()) + "/health/live");
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean up = response.statusCode() == 200;
            out.println((up ? "✓" : "✗") + " server is " + (up ? "live" : "not live (HTTP " + response.statusCode() + ")") + " at " + uri);
            return up;
        } catch (IOException e) {
            out.println("✗ server does not answer at " + uri);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---------------------------------------------------------------------- instance

    private int list(RuntimeConfig config) {
        for (RuntimeConfig.Instance i : config.allInstances()) {
            out.printf("%-16s %-10s %-8s %-24s %s%s%n", i.id(), i.api(), i.contract(), i.authType(), i.baseUrl(),
                    i.tls().isEmpty() ? "" : "  (own TLS settings)");
        }
        return 0;
    }

    private int test(RuntimeConfig.Instance instance) {
        return report(instance, Probe.connectivity(instance)) ? 0 : 1;
    }

    private boolean report(RuntimeConfig.Instance instance, Map<String, Object> result) {
        boolean ok = Boolean.TRUE.equals(result.get("ok"));
        out.println((ok ? "✓" : "✗") + " instance " + instance.id() + " " + result.get("address"));
        if (result.get("steps") instanceof Map<?, ?> steps) {
            steps.forEach((step, value) -> out.println("    " + step + ": " + value));
        }
        if (result.get("hint") != null) {
            out.println("    " + result.get("hint"));
        }
        return ok;
    }

    /**
     * Trust on first use (runtime-config §5.5): shows the chain the host presents and, once the
     * operator confirms, saves it as {@code certs/<id>.pem} next to the config (or prints it as base64),
     * tests the connection with it, and prints the lines to add. Configured material is never replaced.
     */
    private int trust(RuntimeConfig config, RuntimeConfig.Instance instance, boolean base64, boolean yes) {
        if (instance.tls().has("ca")) {
            out.println("✗ instance " + instance.id() + " already has tls.ca; remove it first to fetch a new certificate");
            return 1;
        }
        Map<String, Object> fetched;
        try {
            fetched = Probe.certificate(instance);
        } catch (ToolError e) {
            out.println("✗ " + e.getMessage());
            return 1;
        }
        List<?> chain = (List<?>) fetched.get("chain");
        StringBuilder bundle = new StringBuilder();
        out.println("Certificate chain presented by " + fetched.get("host") + ":");
        for (Object item : chain) {
            Map<?, ?> cert = (Map<?, ?>) item;
            out.println("  subject  " + cert.get("subject"));
            out.println("  issuer   " + cert.get("issuer"));
            out.println("  valid    " + cert.get("notBefore") + " – " + cert.get("notAfter"));
            out.println("  sha256   " + cert.get("sha256"));
            out.println();
            bundle.append(cert.get("pem"));
        }
        if (fetched.get("warning") != null) {
            out.println("! " + fetched.get("warning"));
        }
        if (!yes && !confirm("Trust this certificate for instance " + instance.id() + "? [y/N] ")) {
            out.println("Nothing saved.");
            return 1;
        }

        Path baseDir = config.source().map(p -> p.toAbsolutePath().getParent()).orElse(workingDir.toAbsolutePath());
        ObjectNode tls = instance.tls().deepCopy();
        ObjectNode ca = JsonNodeFactory.instance.objectNode();
        String encoded = Base64.getEncoder().encodeToString(bundle.toString().getBytes(StandardCharsets.US_ASCII));
        Path file = baseDir.resolve("certs").resolve(instance.id() + ".pem");
        if (base64) {
            ca.put("base64", encoded);
        } else {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, bundle.toString());
            } catch (IOException e) {
                out.println("✗ cannot write " + file + ": " + e.getMessage());
                return 1;
            }
            ca.put("path", "./" + baseDir.relativize(file).toString().replace('\\', '/'));
        }
        tls.set("ca", ca);

        // second connection, with the new material only
        RuntimeConfig.Instance tested;
        try {
            tested = new RuntimeConfig.Instance(instance.api(), instance.id(), instance.baseUrl(), instance.contract(), instance.readOnly(),
                    instance.timeoutSeconds(), instance.auth(), tls, TlsBuilder.build(tls, baseDir, "tls"), baseDir);
        } catch (IllegalArgumentException e) {
            out.println("✗ " + e.getMessage());
            return 1;
        }
        if (!report(tested, Probe.connectivity(tested))) {
            out.println("The connection still fails with this certificate; nothing to add.");
            return 1;
        }
        out.println(base64 ? "Add to instance " + instance.id() + ":" : "Saved " + file + ". Add to instance " + instance.id() + ":");
        out.println("    tls:");
        out.println("      ca: { " + (base64 ? "base64: \"" + encoded + "\"" : "path: " + ca.path("path").asText()) + " }");
        return 0;
    }

    private boolean confirm(String question) {
        out.print(question);
        out.flush();
        try {
            String answer = in.readLine();
            return answer != null && (answer.strip().equalsIgnoreCase("y") || answer.strip().equalsIgnoreCase("yes"));
        } catch (IOException e) {
            return false;
        }
    }

    private static RuntimeConfig.Instance instance(RuntimeConfig config, String id) {
        return config.allInstances().stream().filter(i -> i.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No instance named '" + id + "' (configured: "
                        + String.join(", ", config.allInstances().stream().map(RuntimeConfig.Instance::id).toList()) + ")"));
    }
}
