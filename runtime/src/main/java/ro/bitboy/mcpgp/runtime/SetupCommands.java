package ro.bitboy.mcpgp.runtime;

import ro.bitboy.mcpgp.runtime.config.ConfigException;
import ro.bitboy.mcpgp.runtime.config.ConfigFile;
import ro.bitboy.mcpgp.runtime.config.EnvNames;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.diag.Probe;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Commands that write the operator's config (runtime-config §8): {@code init}, {@code instance add},
 * {@code instance remove}. Credentials go to {@code .env} (owner-only); the config gets references.
 * Every change is validated by loading the result; a change that doesn't load is rolled back.
 */
final class SetupCommands {

    private final Manifest manifest;
    private final Path workingDir;
    private final BufferedReader in;
    private final PrintStream out;
    /** Loads the config as the server would (with the new .env values). */
    private final Function<Path, RuntimeConfig> loader;
    /** Read secrets from the terminal without echo (when the commands run in one). */
    private final boolean console;

    SetupCommands(Manifest manifest, Map<String, String> env, Path workingDir, BufferedReader in, PrintStream out,
                  Function<Path, RuntimeConfig> loader, boolean console) {
        this.console = console;
        this.manifest = manifest;
        this.workingDir = workingDir;
        this.in = in;
        this.out = out;
        this.loader = loader;
    }

    // ---------------------------------------------------------------------- init

    int init(Path configFile) throws IOException {
        Path file = configFile != null ? configFile : workingDir.resolve(manifest.serverName() + ".yaml");
        if (Files.exists(file)) {
            out.println("✗ " + file + " already exists; use `instance add` to add instances, or remove the file to start over");
            return 1;
        }
        out.println("Setting up " + manifest.serverTitle() + " " + manifest.serverVersion() + ". Press Enter to accept [defaults].");
        Map<String, Object> content = new LinkedHashMap<>();
        Map<String, String> secrets = new LinkedHashMap<>();

        List<String> transports = manifest.allowed("transports");
        String transport = transports.size() > 1
                ? choose("Transport", transports, manifest.defaultOf("transports")) : transports.get(0);
        if (transport.equals("http")) {
            Map<String, Object> server = ConfigFile.map();
            server.put("transport", "http");
            server.put("host", ask("Address to listen on", "127.0.0.1"));
            server.put("port", Integer.parseInt(ask("Port", "8080")));
            List<String> modes = manifest.allowed("inboundAuth").stream().filter(m -> !m.equals("oauth")).toList();
            String mode = modes.size() > 1 ? choose("Client sign-in", modes, modes.contains("static") ? "static" : modes.get(0)) : modes.get(0);
            Map<String, Object> auth = ConfigFile.map();
            auth.put("mode", mode);
            if (mode.equals("static")) {
                String name = ask("Name of the first client key", "team");
                String variable = EnvNames.of(manifest.serverName(), "key", name);
                String key = randomKey();
                secrets.put(variable, key);
                Map<String, Object> entry = ConfigFile.map();
                entry.put("name", name);
                entry.put("value", "${env:" + variable + "}");
                auth.put("keys", List.of(entry));
                out.println("  Client key \"" + name + "\" (shown once, also saved in .env): " + key);
            }
            server.put("auth", auth);
            content.put("server", server);
        } else if (!manifest.defaultOf("transports").equals(transport)) {
            content.put("server", Map.of("transport", transport));
        }

        boolean several = manifest.apis().size() > 1;
        Map<String, Object> apis = ConfigFile.map();
        for (Manifest.Api api : manifest.apis().values()) {
            out.println();
            out.println("Target server(s) for " + api.title() + ":");
            List<Map<String, Object>> instances = new ArrayList<>();
            do {
                String id = manifest.multiInstance() ? askId(instances) : null;
                instances.add(instance(api, id, secrets));
            } while (manifest.multiInstance() && yes("Add another " + api.title() + " instance?", false));
            Object value = manifest.multiInstance() ? instances : instances.get(0);
            String key = manifest.multiInstance() ? "instances" : "instance";
            if (several) {
                apis.put(api.id(), Map.of(key, value));
            } else {
                content.put(key, value);
            }
        }
        if (several) {
            content.put("apis", apis);
        }

        Files.writeString(file, ConfigFile.document(manifest.serverName() + " configuration, written by `init`.\n"
                + "Secrets are references; their values are in .env next to this file.", content));
        if (!secrets.isEmpty()) {
            ConfigFile.appendEnv(file.toAbsolutePath().getParent().resolve(".env"), secrets);
        }
        out.println();
        out.println("✓ wrote " + file + (secrets.isEmpty() ? "" : " and .env"));
        return check(file, null);
    }

    // ---------------------------------------------------------------------- instance add / remove

    int add(RuntimeConfig config, String id, String apiId) throws IOException {
        if (!manifest.multiInstance()) {
            out.println("✗ this is a single-instance server: it has exactly one instance; edit it, or run `init` for a new config");
            return 1;
        }
        Manifest.Api api = api(apiId);
        if (api == null) {
            return 2;
        }
        if (config.instances().getOrDefault(api.id(), List.of()).stream().anyMatch(i -> i.id().equals(id))) {
            out.println("✗ instance " + id + " already exists");
            return 1;
        }
        if (!id.matches("[a-z0-9][a-z0-9-]{0,39}")) {
            out.println("✗ instance ids use lowercase letters, digits and '-' (at most 40)");
            return 2;
        }
        Path file = config.source().orElse(workingDir.resolve(manifest.serverName() + ".yaml"));
        Map<String, String> secrets = new LinkedHashMap<>();
        Map<String, Object> instance = instance(api, id, secrets);
        ConfigFile configFile = new ConfigFile(file);
        configFile.addInstance(listPath(api), instance);
        if (!secrets.isEmpty()) {
            ConfigFile.appendEnv(file.toAbsolutePath().getParent().resolve(".env"), secrets);
        }
        out.println("✓ added " + id + " to " + file);
        return check(file, configFile);
    }

    int remove(RuntimeConfig config, String id, String apiId) throws IOException {
        Manifest.Api api = api(apiId);
        if (api == null) {
            return 2;
        }
        if (config.source().isEmpty()) {
            out.println("✗ the instances come from environment variables; remove them there");
            return 1;
        }
        ConfigFile configFile = new ConfigFile(config.source().get());
        if (!configFile.removeInstance(listPath(api), id)) {
            out.println("✗ no instance " + id + " in " + configFile.path());
            return 1;
        }
        try {
            loader.apply(configFile.path());
        } catch (ConfigException e) {
            configFile.restore();
            out.println("✗ without " + id + " the config doesn't load, so nothing was changed:");
            e.problems().forEach(p -> out.println("    " + p));
            return 1;
        }
        out.println("✓ removed " + id + " from " + configFile.path() + " (its values in .env are left; delete them if unused)");
        return 0;
    }

    // ---------------------------------------------------------------------- shared

    /** Prompts for one instance; secret values go to {@code secrets} under their variable names. */
    private Map<String, Object> instance(Manifest.Api api, String id, Map<String, String> secrets) {
        Map<String, Object> instance = ConfigFile.map();
        if (id != null) {
            instance.put("id", id);
        }
        instance.put("baseUrl", askRequired("Address (e.g. orders.example.com, or https://host:8443/api)"));
        if (api.contracts().size() > 1) {
            List<String> options = new ArrayList<>(List.of("auto"));
            options.addAll(api.contracts().keySet());
            instance.put("contract", choose("Contract version", options, "auto"));
        }
        List<String> types = new ArrayList<>(api.supportedAuth().stream().filter(t -> !t.equals("mtls")).toList());
        types.add("none");
        String type = choose("Sign-in", types, types.get(0));
        Map<String, Object> auth = ConfigFile.map();
        auth.put("type", type);
        String who = id != null ? id : api.id();
        switch (type) {
            case "basic" -> {
                auth.put("username", askRequired("Username"));
                auth.put("password", secret(who, "password", "Password", secrets));
            }
            case "bearer" -> auth.put("token", secret(who, "token", "Token", secrets));
            case "apiKey" -> auth.put("value", secret(who, "api_key", "API key", secrets));
            case "oauth2ClientCredentials" -> {
                auth.put("tokenUrl", askRequired("Token URL"));
                auth.put("clientId", askRequired("Client id"));
                auth.put("clientSecret", secret(who, "client_secret", "Client secret", secrets));
                String scopes = ask("Scopes (comma-separated, empty for none)", "");
                if (!scopes.isBlank()) {
                    auth.put("scopes", List.of(scopes.split("\\s*,\\s*")));
                }
            }
            default -> {
            }
        }
        if (!type.equals("none")) {
            instance.put("auth", auth);
        }
        return instance;
    }

    private String secret(String who, String field, String label, Map<String, String> secrets) {
        String variable = EnvNames.of(manifest.serverName(), who, field);
        secrets.put(variable, askSecret(label));
        return "${env:" + variable + "}";
    }

    /** Loads the written config and tests each instance; rolls back an edit that doesn't load. */
    private int check(Path file, ConfigFile rollback) throws IOException {
        RuntimeConfig loaded;
        try {
            loaded = loader.apply(file);
        } catch (ConfigException e) {
            out.println("✗ the config doesn't load" + (rollback == null ? "; fix it and run `doctor`:" : ", so the change was undone:"));
            e.problems().forEach(p -> out.println("    " + p));
            if (rollback != null) {
                rollback.restore();
            }
            return 1;
        }
        boolean ok = true;
        for (RuntimeConfig.Instance instance : loaded.allInstances()) {
            Map<String, Object> result = Probe.connectivity(instance);
            boolean up = Boolean.TRUE.equals(result.get("ok"));
            out.println((up ? "✓" : "✗") + " instance " + instance.id() + " " + result.get("address"));
            if (!up && result.get("steps") instanceof Map<?, ?> steps) {
                steps.forEach((step, value) -> out.println("    " + step + ": " + value));
                if (String.valueOf(steps.get("tls")).startsWith("failed")) {
                    out.println("    If the server uses an internal or self-signed certificate: `instance trust " + instance.id() + "`");
                }
            }
            ok &= up;
        }
        return ok ? 0 : 1;
    }

    private Manifest.Api api(String apiId) {
        if (manifest.apis().size() == 1) {
            return manifest.apis().values().iterator().next();
        }
        Manifest.Api api = apiId == null ? null : manifest.apis().get(apiId);
        if (api == null) {
            out.println("✗ this server combines several APIs; choose one with --api (" + String.join(", ", manifest.apis().keySet()) + ")");
        }
        return api;
    }

    private List<String> listPath(Manifest.Api api) {
        return manifest.apis().size() > 1 ? List.of("apis", api.id(), "instances") : List.of("instances");
    }

    private String askId(List<Map<String, Object>> existing) {
        while (true) {
            String id = askRequired("Instance id (what agents pass as `instance`, e.g. prod-eu)");
            if (!id.matches("[a-z0-9][a-z0-9-]{0,39}")) {
                out.println("  lowercase letters, digits and '-', at most 40");
            } else if (existing.stream().anyMatch(i -> id.equals(i.get("id")))) {
                out.println("  already used");
            } else {
                return id;
            }
        }
    }

    private String ask(String question, String defaultValue) {
        out.print(question + (defaultValue.isEmpty() ? "" : " [" + defaultValue + "]") + ": ");
        out.flush();
        String answer = line();
        return answer == null || answer.isBlank() ? defaultValue : answer.strip();
    }

    private String askRequired(String question) {
        while (true) {
            String answer = ask(question, "");
            if (!answer.isEmpty()) {
                return answer;
            }
            if (endOfInput) {
                throw new IllegalArgumentException("input ended before setup was complete");
            }
        }
    }

    private String askSecret(String question) {
        if (console && System.console() != null) {
            char[] value = System.console().readPassword(question + " (stored in .env, not shown): ");
            return value == null ? "" : new String(value);
        }
        return askRequired(question + " (stored in .env)");
    }

    private String choose(String question, List<String> options, String defaultValue) {
        while (true) {
            String answer = ask(question + " (" + String.join(" / ", options) + ")", defaultValue);
            if (options.contains(answer)) {
                return answer;
            }
            out.println("  one of: " + String.join(", ", options));
            if (endOfInput) {
                throw new IllegalArgumentException("input ended before setup was complete");
            }
        }
    }

    private boolean yes(String question, boolean defaultValue) {
        String answer = ask(question + (defaultValue ? " [Y/n]" : " [y/N]"), "");
        return answer.isEmpty() ? defaultValue : answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes");
    }

    private boolean endOfInput;

    private String line() {
        try {
            String line = in.readLine();
            if (line == null) {
                endOfInput = true;
            }
            return line;
        } catch (IOException e) {
            endOfInput = true;
            return null;
        }
    }

    private static String randomKey() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return "mcp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
