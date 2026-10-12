package ro.bitboy.mcpgp.platform;

import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.packager.Minisign;
import ro.bitboy.mcpgp.platform.service.YamlEdit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code setup} (docs/guides/self-hosting.md): writes the files to run this image with Docker Compose
 * into a folder: the compose files (pointing at the image, without a build section), {@code platform.yaml},
 * {@code .env}, and {@code secrets/} with generated passwords and a signing key. It never overwrites a
 * file; {@code --upgrade} rewrites only the compose files and the image in {@code .env}.
 *
 * <pre>
 * docker run --rm -it --user "$(id -u):$(id -g)" -v "$PWD":/setup &lt;image&gt; setup
 * </pre>
 */
final class Setup {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String RESOURCES = "/mcpgp/deploy/";

    private final Map<String, String> env;
    private final BufferedReader in;
    private final PrintStream out;

    // answers
    Path dir;
    String image;
    String publicUrl;
    String workspace;
    String store;
    Boolean sign;
    String oidcIssuer;
    String oidcClientId;
    Boolean oidcSecret;
    String admin = "admin";
    String bind = "127.0.0.1";
    String port = "8443";
    boolean bindGiven;
    boolean portGiven;
    /** HTTPS: self-signed or files (the platform serves it), proxy (yours does), or null (ask, when the address is https). */
    String tls;
    String cert;
    String key;
    boolean yes;
    boolean upgrade;

    Setup(Map<String, String> env, BufferedReader in, PrintStream out) {
        this.env = env;
        this.in = in;
        this.out = out;
    }

    static final String USAGE = """
            Usage: setup [--out <dir>] [--public-url <https://…>] [--workspace <name>] [--store postgres|files]
                         [--sign | --no-sign] [--oidc-issuer <url> --oidc-client-id <id> [--oidc-public-client]]
                         [--admin <name>] [--bind <address>] [--port <port>] [--image <image>] [--yes] [--upgrade]
                         [--tls self-signed|files|proxy [--cert <certificate.pem> --key <key.pem>]]
              Writes compose.yaml (and compose.postgres/signing/oidc/tls.yaml), platform.yaml, .env and secrets/
              into <dir> (default /setup in the image, else the working directory). Asks for what isn't
              given, unless --yes. Existing files are kept; --upgrade rewrites the compose files and
              MCPGP_IMAGE for this image. --tls in a folder that exists switches how HTTPS is done:
              self-signed (the platform serves it with a certificate made here), files (your certificate
              and key, paths inside the folder), proxy (yours does; the platform listens on 127.0.0.1).
              With --yes and an https:// address, the default is proxy.""";

    int run(String[] args) {
        try {
            if (!parse(args)) {
                out.println(USAGE);
                return 2;
            }
            if (dir == null) {
                dir = Files.isDirectory(Path.of("/setup")) ? Path.of("/setup") : Path.of("").toAbsolutePath();
            }
            if (image == null) {
                image = env.getOrDefault("MCPGP_PLATFORM_IMAGE", "mcpgp-platform:" + Compiler.VERSION);
            }
            if (!Files.isDirectory(dir)) {
                out.println("✗ " + dir + " is not a folder; mount one at /setup (docker run -v \"$PWD\":/setup …)");
                return 1;
            }
            if (tls != null && !List.of("self-signed", "files", "proxy").contains(tls)) {
                throw new IllegalArgumentException("--tls: self-signed, files or proxy");
            }
            if (upgrade) {
                return upgrade();
            }
            return tls != null && Files.exists(dir.resolve(".env")) ? switchTls() : write();
        } catch (IllegalArgumentException e) {
            out.println("✗ " + e.getMessage());
            return 2;
        } catch (IOException e) {
            out.println("✗ " + e.getMessage());
            return 1;
        }
    }

    private boolean parse(String[] args) {
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            String value = i + 1 < args.length ? args[i + 1] : null;
            switch (a) {
                case "--out" -> { dir = Path.of(need(a, value)); i++; }
                case "--image" -> { image = need(a, value); i++; }
                case "--public-url" -> { publicUrl = need(a, value); i++; }
                case "--workspace" -> { workspace = need(a, value); i++; }
                case "--store" -> { store = need(a, value); i++; }
                case "--admin" -> { admin = need(a, value); i++; }
                case "--bind" -> { bind = need(a, value); bindGiven = true; i++; }
                case "--port" -> { port = need(a, value); portGiven = true; i++; }
                case "--tls" -> { tls = need(a, value); i++; }
                case "--cert" -> { cert = need(a, value); i++; }
                case "--key" -> { key = need(a, value); i++; }
                case "--oidc-issuer" -> { oidcIssuer = need(a, value); i++; }
                case "--oidc-client-id" -> { oidcClientId = need(a, value); i++; }
                case "--oidc-public-client" -> oidcSecret = false;
                case "--sign" -> sign = true;
                case "--no-sign" -> sign = false;
                case "--yes", "-y" -> yes = true;
                case "--upgrade" -> upgrade = true;
                case "--help", "-h" -> { return false; }
                default -> throw new IllegalArgumentException("unknown option " + a + "\n" + USAGE);
            }
        }
        return true;
    }

    private static String need(String option, String value) {
        if (value == null || value.startsWith("--")) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return value;
    }

    // ------------------------------------------------------------------ a new folder

    private int write() throws IOException {
        out.println("mcpgp platform setup: the files to run " + image + " with Docker Compose, in " + dir);
        out.println();
        ask();
        validate();

        List<String> composeFiles = new ArrayList<>(List.of("compose.yaml"));
        if (store.equals("postgres")) {
            composeFiles.add("compose.postgres.yaml");
        }
        if (sign) {
            composeFiles.add("compose.signing.yaml");
        }
        if (oidcIssuer != null && oidcSecret) {
            composeFiles.add("compose.oidc.yaml");
        }
        if (tlsHere()) {
            composeFiles.add(tls.equals("files") ? "compose.tls-files.yaml" : "compose.tls.yaml");
            if (!bindGiven) {
                bind = "0.0.0.0";
            }
            if (!portGiven) {
                port = String.valueOf(TlsFiles.port(publicUrl));
            }
        }
        for (String file : composeFiles) {
            put(file, compose(file), "");
        }
        put("platform.yaml", platformYaml(), "");
        put(".env", env(composeFiles), "(image, public address, the compose files to combine)");
        put(".gitignore", "# never commit these\n.env\nsecrets/\n", "");

        Path secrets = dir.resolve("secrets");
        Files.createDirectories(secrets);
        permissions(secrets, "rwx------");
        secret("admin_password", password(), "generated; the first administrator's password");
        if (store.equals("postgres")) {
            secret("db_password", password(), "generated; PostgreSQL's and the platform's");
        }
        if (sign) {
            if (Files.exists(secrets.resolve("signing_key"))) {
                out.println("  kept    secrets/signing_key");
            } else {
                try {
                    Minisign.Keys keys = Minisign.generate();
                    secret("signing_key", Minisign.secretKeyFile(keys), "generated; back it up, it can't be recreated");
                    put("signing_key.pub", Minisign.publicKeyFile(keys), "(the public key: publish it, customers verify packages with it)");
                } catch (java.security.GeneralSecurityException e) {
                    throw new IOException("the signing key can't be generated: " + e.getMessage());
                }
            }
        }
        if (tlsHere()) {
            tlsFiles();
        }
        if (oidcIssuer != null && oidcSecret && !Files.exists(secrets.resolve("oidc_client_secret"))) {
            out.println("  todo    secrets/oidc_client_secret: put the client secret from your identity provider there (chmod 644)");
        }

        out.println();
        out.println("Next:");
        out.println("  docker compose run --rm platform check     # what would go wrong, before starting");
        out.println("  docker compose up -d --wait");
        if (tlsHere()) {
            out.println("  the platform serves HTTPS on port " + port + " itself" + (tls.equals("self-signed")
                    ? "; browsers warn about the self-signed certificate until you trust tls/certificate.pem or replace it:"
                    + "\n    setup --tls files --cert <certificate.pem> --key <key.pem>  (files you put in this folder)" : ""));
        } else if (publicUrl.startsWith("https://")) {
            out.println("  then put TLS in front of " + bind + ":" + port + " on " + TlsFiles.host(publicUrl) + ":" + TlsFiles.port(publicUrl)
                    + " (Caddy: reverse_proxy " + bind + ":" + port + "), or let the platform do it: setup --tls self-signed");
        }
        out.println("  and sign in at " + publicUrl + " as " + admin + " (the password is in secrets/admin_password)");
        if (oidcIssuer != null) {
            out.println("  company sign-in: register the redirect URI " + publicUrl + "/login/oauth2/code/oidc and map your groups");
            out.println("  to roles in platform.yaml (auth.oidc.roles; the ones written there are examples)");
        }
        out.println("  back up secrets/ and .env somewhere safe" + (sign ? ": the signing key can't be recreated" : ""));
        return 0;
    }

    private void ask() throws IOException {
        boolean interactive = !yes && in != null;
        if (publicUrl == null) {
            publicUrl = interactive ? prompt("Public address (https://…)", "http://localhost:8443") : "http://localhost:8443";
        }
        if (workspace == null) {
            workspace = interactive ? prompt("Workspace name (in mcpgp:// references)", PlatformConfig.DEFAULT_WORKSPACE) : PlatformConfig.DEFAULT_WORKSPACE;
        }
        if (store == null) {
            store = interactive ? prompt("Store [postgres/files]", "postgres") : "postgres";
        }
        if (sign == null) {
            sign = !interactive || yesNo(prompt("Sign packages? [Y/n]", "y"));
        }
        if (tls == null && publicUrl.startsWith("https://")) {
            if (interactive) {
                out.println("HTTPS for " + publicUrl + ":");
                out.println("  1) here, with a self-signed certificate for now (replace it with yours later)");
                out.println("  2) here, with my certificate files (in this folder)");
                out.println("  3) my own proxy or ingress does it (the platform stays plain HTTP behind it)");
                String answer = prompt("Choose 1, 2 or 3", "1");
                tls = answer.startsWith("2") ? "files" : answer.startsWith("3") ? "proxy" : "self-signed";
                if (tls.equals("files")) {
                    cert = cert != null ? cert : prompt("Certificate file (PEM, the full chain), in this folder", "certificate.pem");
                    key = key != null ? key : prompt("Its private key (PEM), in this folder", "key.pem");
                }
            } else {
                tls = "proxy";      // scripts: as before 0.2.1
            }
        }
        if (oidcIssuer == null && interactive) {
            String issuer = prompt("Company sign-in: OIDC issuer (empty to skip)", "");
            oidcIssuer = issuer.isBlank() ? null : issuer;
        }
        if (oidcIssuer != null) {
            if (oidcClientId == null) {
                oidcClientId = interactive ? prompt("OIDC client id", "mcpgp") : "mcpgp";
            }
            if (oidcSecret == null) {
                oidcSecret = !interactive || yesNo(prompt("Does the client have a secret? [Y/n]", "y"));
            }
        }
    }

    private String prompt(String question, String fallback) throws IOException {
        out.print(question + (fallback.isEmpty() ? "" : " [" + fallback + "]") + ": ");
        out.flush();
        String line = in.readLine();
        return line == null || line.isBlank() ? fallback : line.strip();
    }

    private static boolean yesNo(String answer) {
        return !answer.toLowerCase().startsWith("n");
    }

    private void validate() {
        if (!publicUrl.startsWith("https://") && !publicUrl.startsWith("http://")) {
            throw new IllegalArgumentException("the public address starts with https:// (or http:// for a local test)");
        }
        publicUrl = publicUrl.replaceAll("/+$", "");
        if (!workspace.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("workspace: lowercase letters, digits and '-'");
        }
        if (!store.equals("postgres") && !store.equals("files")) {
            throw new IllegalArgumentException("store: postgres or files");
        }
        if (!admin.matches("[A-Za-z0-9][A-Za-z0-9._@-]{0,63}")) {
            throw new IllegalArgumentException("admin: letters, digits, '.', '_', '@' and '-'");
        }
        if (!port.matches("\\d{1,5}")) {
            throw new IllegalArgumentException("port: a number");
        }
        validateTls();
        if (oidcIssuer != null && !oidcIssuer.startsWith("https://")) {
            throw new IllegalArgumentException("the OIDC issuer starts with https://");
        }
    }

    // ------------------------------------------------------------------ HTTPS

    private void validateTls() {
        if (tlsHere() && !publicUrl.startsWith("https://")) {
            throw new IllegalArgumentException("--tls " + tls + " needs an https:// public address (MCPGP_PUBLIC_URL in .env)");
        }
        if ("files".equals(tls) && (cert == null || key == null)) {
            throw new IllegalArgumentException("--tls files needs --cert <certificate.pem> and --key <key.pem>");
        }
    }

    private boolean tlsHere() {
        return "self-signed".equals(tls) || "files".equals(tls);
    }

    /** The certificate files: made here (self-signed), or the given ones copied in and checked. */
    private void tlsFiles() throws IOException {
        Path secrets = dir.resolve("secrets");
        Path tlsDir = dir.resolve("tls");
        Files.createDirectories(secrets);
        Files.createDirectories(tlsDir);
        if (tls.equals("self-signed")) {
            if (Files.exists(secrets.resolve("tls_keystore.p12"))) {
                out.println("  kept    secrets/tls_keystore.p12");
                return;
            }
            String password = password().strip();
            Path pem = tlsDir.resolve("certificate.pem");
            Files.deleteIfExists(pem);
            TlsFiles.selfSigned(TlsFiles.host(publicUrl), secrets.resolve("tls_keystore.p12"), password, pem);
            permissions(secrets.resolve("tls_keystore.p12"), "rw-r--r--");
            Files.writeString(secrets.resolve("tls_keystore_password"), password + "\n");
            permissions(secrets.resolve("tls_keystore_password"), "rw-r--r--");
            out.println("  wrote   secrets/tls_keystore.p12 and secrets/tls_keystore_password (a self-signed certificate for "
                    + TlsFiles.host(publicUrl) + ", valid 1 year)");
            out.println("  wrote   tls/certificate.pem (that certificate: import it where browsers and clients should trust it)");
            return;
        }
        Path certFile = dir.resolve(cert).normalize();
        Path keyFile = dir.resolve(key).normalize();
        if (!certFile.startsWith(dir) || !keyFile.startsWith(dir) || !Files.isRegularFile(certFile) || !Files.isRegularFile(keyFile)) {
            throw new IllegalArgumentException("--cert and --key are files in " + dir + " (put them there first)");
        }
        try {
            java.security.cert.X509Certificate certificate;
            try (InputStream inCert = Files.newInputStream(certFile)) {
                certificate = (java.security.cert.X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(inCert);
            }
            TlsFiles.privateKey(Files.readString(keyFile));
            if (!TlsFiles.covers(TlsFiles.names(certificate), TlsFiles.host(publicUrl))) {
                out.println("  warning the certificate is for " + TlsFiles.names(certificate) + ", not " + TlsFiles.host(publicUrl));
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("the certificate or key can't be read: " + e.getMessage());
        }
        Files.copy(certFile, tlsDir.resolve("certificate.pem"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(keyFile, secrets.resolve("tls_key"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        permissions(secrets.resolve("tls_key"), "rw-r--r--");
        out.println("  wrote   tls/certificate.pem and secrets/tls_key (from " + cert + " and " + key + "; delete those copies when it works)");
    }

    /** {@code --tls} in a folder that exists: the TLS files, and COMPOSE_FILE, MCPGP_BIND and MCPGP_PORT in .env. */
    private int switchTls() throws IOException {
        Path envFile = dir.resolve(".env");
        String text = Files.readString(envFile);
        Map<String, String> current = new LinkedHashMap<>();
        text.lines().filter(l -> l.matches("[A-Z_]+=.*")).forEach(l -> current.put(l.substring(0, l.indexOf('=')), l.substring(l.indexOf('=') + 1)));
        boolean urlGiven = publicUrl != null;
        if (!urlGiven) {
            publicUrl = current.getOrDefault("MCPGP_PUBLIC_URL", "");
        }
        out.println("mcpgp platform setup --tls " + tls + ": HTTPS for " + publicUrl + ", in " + dir);
        validateTls();
        List<String> files = new ArrayList<>(List.of(current.getOrDefault("COMPOSE_FILE", "compose.yaml").split(":")));
        files.removeIf(f -> f.startsWith("compose.tls"));
        Map<String, String> values = new LinkedHashMap<>();
        if (urlGiven) {
            values.put("MCPGP_PUBLIC_URL", publicUrl.replaceAll("/+$", ""));
        }
        if (tlsHere()) {
            String file = tls.equals("files") ? "compose.tls-files.yaml" : "compose.tls.yaml";
            files.add(file);
            if (!Files.exists(dir.resolve(file))) {
                Files.writeString(dir.resolve(file), compose(file));
                out.println("  wrote   " + file);
            }
            tlsFiles();
            values.put("MCPGP_BIND", bindGiven ? bind : "0.0.0.0");
            values.put("MCPGP_PORT", portGiven ? port : String.valueOf(TlsFiles.port(publicUrl)));
        } else {
            values.put("MCPGP_BIND", bindGiven ? bind : "127.0.0.1");
            values.put("MCPGP_PORT", portGiven ? port : "8443");
        }
        values.put("COMPOSE_FILE", String.join(":", files));
        Files.writeString(envFile, setKeys(text, values));
        out.println("  updated .env (COMPOSE_FILE, MCPGP_BIND=" + values.get("MCPGP_BIND") + ", MCPGP_PORT=" + values.get("MCPGP_PORT") + ")");
        out.println();
        out.println("Next: docker compose run --rm platform check && docker compose up -d --force-recreate --wait");
        if (tls.equals("proxy")) {
            out.println("Your proxy serves " + publicUrl + " and forwards to 127.0.0.1:" + values.get("MCPGP_PORT") + ".");
        }
        return 0;
    }

    // ------------------------------------------------------------------ --upgrade

    private int upgrade() throws IOException {
        out.println("mcpgp platform setup --upgrade: the compose files and MCPGP_IMAGE for " + image + ", in " + dir);
        int changed = 0;
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().matches("compose(\\.[a-z]+)?\\.yaml")).sorted().toList()) {
                String name = file.getFileName().toString();
                if (resource(name) == null) {
                    out.println("  kept    " + name + " (not one of this image's files)");
                    continue;
                }
                Files.writeString(file, compose(name));
                out.println("  updated " + name);
                changed++;
            }
        }
        Path envFile = dir.resolve(".env");
        if (Files.exists(envFile)) {
            Files.writeString(envFile, setKeys(Files.readString(envFile), Map.of("MCPGP_IMAGE", image)));
            out.println("  updated .env (MCPGP_IMAGE=" + image + ")");
            changed++;
        }
        if (changed == 0) {
            out.println("✗ no compose files or .env in " + dir + "; run setup without --upgrade first");
            return 1;
        }
        out.println();
        out.println("Next: back up first, then docker compose pull && docker compose up -d --wait");
        out.println("secrets/, platform.yaml and the rest of .env are unchanged.");
        return 0;
    }

    // ------------------------------------------------------------------ the files

    /** A compose file of this image: no build section (there's no repository on the server), its own header. */
    String compose(String name) {
        String text = resource(name);
        if (text == null) {
            throw new UncheckedIOException(new IOException(name + " is not in this image"));
        }
        if (name.equals("compose.yaml")) {
            text = YamlEdit.apply(text, List.of(new YamlEdit.Change("remove", "/services/platform/build", null)));
        }
        List<String> lines = new ArrayList<>(text.lines().toList());
        while (!lines.isEmpty() && lines.get(0).startsWith("#")) {
            lines.remove(0);
        }
        String header = "# Written by `setup` of " + image + " (mcpgp " + Compiler.VERSION + "); docs/guides/self-hosting.md.\n"
                + (name.equals("compose.yaml") ? "# The files to combine are in COMPOSE_FILE in .env. A newer image: run its setup --upgrade.\n" : "");
        return header + String.join("\n", lines) + "\n";
    }

    private String platformYaml() {
        String text = resource("platform.yaml");
        if (oidcIssuer == null) {
            return text;
        }
        Map<String, Object> oidc = new LinkedHashMap<>();
        oidc.put("issuer", oidcIssuer);
        oidc.put("clientId", oidcClientId);
        if (oidcSecret) {
            oidc.put("clientSecret", "${file:/run/secrets/oidc_client_secret}");
        }
        oidc.put("label", "Company account");
        oidc.put("groupsClaim", "groups");
        oidc.put("roles", Map.of("mcpgp-admins", "admin", "mcpgp-builders", "builder"));
        return YamlEdit.apply(text, List.of(new YamlEdit.Change("set", "/auth/oidc",
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(oidc))));
    }

    private String env(List<String> composeFiles) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("MCPGP_IMAGE", image);
        values.put("COMPOSE_FILE", String.join(":", composeFiles));
        values.put("MCPGP_PUBLIC_URL", publicUrl);
        values.put("MCPGP_WORKSPACE", workspace);
        values.put("MCPGP_ADMIN", admin);
        values.put("MCPGP_BIND", bind);
        values.put("MCPGP_PORT", port);
        return setKeys(resource(".env.example"), values);
    }

    /** Sets {@code KEY=value} lines (a commented-out {@code # KEY=…} line becomes the setting); missing keys go at the end. */
    static String setKeys(String text, Map<String, String> values) {
        List<String> lines = new ArrayList<>(text.lines().toList());
        Map<String, String> missing = new LinkedHashMap<>(values);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (Map.Entry<String, String> v : values.entrySet()) {
                if (line.matches("#?\\s*" + v.getKey() + "=.*") && missing.containsKey(v.getKey())) {
                    lines.set(i, v.getKey() + "=" + v.getValue());
                    missing.remove(v.getKey());
                }
            }
        }
        missing.forEach((k, v) -> lines.add(k + "=" + v));
        return String.join("\n", lines) + "\n";
    }

    private static String resource(String name) {
        try (InputStream in = Setup.class.getResourceAsStream(RESOURCES + name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void put(String name, String content, String note) throws IOException {
        Path file = dir.resolve(name);
        if (Files.exists(file)) {
            out.println("  kept    " + name + " (exists)");
            return;
        }
        Files.writeString(file, content);
        out.println("  wrote   " + name + (note.isEmpty() ? "" : " " + note));
    }

    /** A secret file: readable for the containers' own users (644), the folder keeps others out (700). Never printed. */
    private void secret(String name, String content, String note) throws IOException {
        Path file = dir.resolve("secrets").resolve(name);
        if (Files.exists(file)) {
            out.println("  kept    secrets/" + name + " (exists)");
            return;
        }
        Files.writeString(file, content);
        permissions(file, "rw-r--r--");
        out.println("  wrote   secrets/" + name + " (" + note + ")");
    }

    private static String password() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes) + "\n";
    }

    private static void permissions(Path path, String mode) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
        } catch (IOException | UnsupportedOperationException e) {
            // not a POSIX file system
        }
    }
}
