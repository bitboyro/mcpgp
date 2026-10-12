package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code platform.yaml} (spec/platform-api.md §7): where the store lives and where the API listens.
 * Environment variables {@code MCPGP_PLATFORM_<SECTION>__<KEY>} override the file (the list is in
 * spec/platform-api.md §7).
 *
 * @param postgres the PostgreSQL store, or null for the file store at {@code storageRoot}
 * @param admin    the first administrator, created at start when the store has no identities yet, or null
 */
public record PlatformConfig(Path storageRoot, String host, int port, String signingKey, Retention retention, String workspace,
                             boolean localAccounts, Guardrails guardrails, Oidc oidc, Ldap ldap, String publicUrl, Postgres postgres,
                             Admin admin, Tls tls) {

    public PlatformConfig(Path storageRoot, String host, int port, String signingKey, Retention retention, String workspace,
                          boolean localAccounts, Guardrails guardrails, Oidc oidc, Ldap ldap, String publicUrl, Postgres postgres, Admin admin) {
        this(storageRoot, host, port, signingKey, retention, workspace, localAccounts, guardrails, oidc, ldap, publicUrl, postgres, admin, null);
    }

    /**
     * {@code http.tls}: the platform serves HTTPS itself, with a certificate and its private key as PEM
     * files ({@code certificate}, {@code privateKey}: paths), or a PKCS12 {@code keystore} (a path) and
     * its {@code password} (a reference). Without it the platform serves plain HTTP, for a proxy in front.
     * Renewed files are picked up without a restart.
     */
    public record Tls(String certificate, String privateKey, String keystore, String password) {
        public boolean pem() {
            return keystore == null;
        }
    }

    public PlatformConfig withTls(Tls tls) {
        return new PlatformConfig(storageRoot, host, port, signingKey, retention, workspace, localAccounts, guardrails, oidc, ldap, publicUrl,
                postgres, admin, tls);
    }

    public PlatformConfig(Path storageRoot, String host, int port, String signingKey, Retention retention, String workspace,
                          boolean localAccounts, Guardrails guardrails, Oidc oidc, Ldap ldap, String publicUrl) {
        this(storageRoot, host, port, signingKey, retention, workspace, localAccounts, guardrails, oidc, ldap, publicUrl, null, null);
    }

    public PlatformConfig(Path storageRoot, String host, int port, String signingKey, Retention retention, String workspace,
                          boolean localAccounts, Guardrails guardrails) {
        this(storageRoot, host, port, signingKey, retention, workspace, localAccounts, guardrails, null, null, null);
    }

    /** {@code storage.type: postgres}: a JDBC URL, the account, its password as a reference, and the schema. */
    public record Postgres(String url, String user, String password, String schema, int poolSize) {
    }

    /** The first administrator, a local account; {@code password} is a reference. */
    public record Admin(String name, String password) {
    }

    public PlatformConfig withPostgres(Postgres postgres) {
        return new PlatformConfig(storageRoot, host, port, signingKey, retention, workspace, localAccounts, guardrails, oidc, ldap, publicUrl,
                postgres, admin, tls);
    }

    public PlatformConfig withAdmin(Admin admin) {
        return new PlatformConfig(storageRoot, host, port, signingKey, retention, workspace, localAccounts, guardrails, oidc, ldap, publicUrl,
                postgres, admin, tls);
    }

    /**
     * Company sign-in with OpenID Connect (spec/platform-api.md §3): authorization code with PKCE.
     * {@code roles} maps values of the {@code groupsClaim} claim to platform roles.
     */
    public record Oidc(String issuer, String clientId, String clientSecret, List<String> scopes, String groupsClaim, Map<String, String> roles,
                       String label) {
    }

    /**
     * Company sign-in against a directory (spec/platform-api.md §3): a search for the person with the
     * service account ({@code bindDn}), then a bind as them; or Active Directory by domain. Group names
     * (their {@code cn}) map to platform roles in {@code roles}.
     */
    public record Ldap(String url, boolean startTls, String bindDn, String bindPassword, String userSearchBase, String userSearchFilter,
                       String groupSearchBase, String groupSearchFilter, String activeDirectoryDomain, Map<String, String> roles) {
        public boolean activeDirectory() {
            return activeDirectoryDomain != null;
        }
    }

    /** Agent quotas (spec/platform-api.md §5). */
    public record Guardrails(int agentBuildsPerHour, int agentVersionsPerHour) {
        public static final Guardrails DEFAULT = new Guardrails(20, 60);
    }

    public PlatformConfig(Path storageRoot, String host, int port, String signingKey, Retention retention, String workspace) {
        this(storageRoot, host, port, signingKey, retention, workspace, true, Guardrails.DEFAULT);
    }

    /** Build retention (spec/platform-api.md §2.2); released builds and the last {@code keepLast} are never touched. */
    public record Retention(int archiveAfterDays, int deleteArchivedAfterDays, int keepLast) {
        public static final Retention DEFAULT = new Retention(90, 365, 10);
    }

    /** The name in {@code mcpgp://<workspace>/<kind>/<name>} references this platform resolves. */
    public static final String DEFAULT_WORKSPACE = "local";

    public PlatformConfig(Path storageRoot, String host, int port) {
        this(storageRoot, host, port, null, Retention.DEFAULT, DEFAULT_WORKSPACE);
    }

    public PlatformConfig(Path storageRoot, String host, int port, String signingKey, Retention retention) {
        this(storageRoot, host, port, signingKey, retention, DEFAULT_WORKSPACE);
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public static PlatformConfig load(Path file, Map<String, String> env) {
        JsonNode doc = YAML.createObjectNode();
        if (file != null) {
            try {
                JsonNode read = YAML.readTree(file.toFile());
                if (read != null && read.isObject()) {
                    doc = read;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + file, e);
            }
        }
        String type = env.getOrDefault("MCPGP_PLATFORM_STORAGE__TYPE", doc.at("/storage/type").asText("files"));
        if (!type.equals("files") && !type.equals("postgres")) {
            throw new IllegalArgumentException("storage.type: files or postgres, not " + type);
        }
        Postgres postgres = type.equals("postgres") ? postgres(doc.path("storage"), env) : null;
        String root = env.getOrDefault("MCPGP_PLATFORM_STORAGE__ROOT",
                doc.at("/storage/root").asText(Path.of(System.getProperty("user.home"), ".mcpgp-platform").toString()));
        String host = env.getOrDefault("MCPGP_PLATFORM_HTTP__HOST", doc.at("/http/host").asText("127.0.0.1"));
        int port = Integer.parseInt(env.getOrDefault("MCPGP_PLATFORM_HTTP__PORT", doc.at("/http/port").asText("8443")));
        Path storage = Path.of(root);
        if (!storage.isAbsolute() && file != null) {
            storage = file.toAbsolutePath().getParent().resolve(storage).normalize();
        }
        String signing = env.getOrDefault("MCPGP_PLATFORM_SIGNING__KEY", doc.at("/signing/key").asText(null));
        JsonNode retention = doc.path("retention");
        Retention r = new Retention(retention.path("archiveAfterDays").asInt(Retention.DEFAULT.archiveAfterDays()),
                retention.path("deleteArchivedAfterDays").asInt(Retention.DEFAULT.deleteArchivedAfterDays()),
                retention.path("keepLast").asInt(Retention.DEFAULT.keepLast()));
        String workspace = env.getOrDefault("MCPGP_PLATFORM_WORKSPACE", doc.path("workspace").asText(DEFAULT_WORKSPACE));
        if (!workspace.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("workspace: lowercase letters, digits and '-'");
        }
        boolean local = doc.at("/auth/local/enabled").asBoolean(true);
        JsonNode guard = doc.path("guardrails");
        Guardrails g = new Guardrails(guard.path("agentBuildsPerHour").asInt(Guardrails.DEFAULT.agentBuildsPerHour()),
                guard.path("agentVersionsPerHour").asInt(Guardrails.DEFAULT.agentVersionsPerHour()));
        String publicUrl = env.getOrDefault("MCPGP_PLATFORM_HTTP__PUBLIC_URL", doc.at("/http/publicUrl").asText(null));
        if (publicUrl != null && !publicUrl.startsWith("https://") && !publicUrl.startsWith("http://")) {
            throw new IllegalArgumentException("http.publicUrl must start with https:// (or http:// for a local test)");
        }
        String adminName = env.getOrDefault("MCPGP_PLATFORM_ADMIN__NAME", doc.at("/admin/name").asText(null));
        Admin admin = null;
        if (adminName != null && !adminName.isBlank()) {
            String password = env.getOrDefault("MCPGP_PLATFORM_ADMIN__PASSWORD", doc.at("/admin/password").asText(null));
            if (password == null || !Secrets.isReference(password)) {
                throw new IllegalArgumentException("admin.password is required and must be a reference (${env:…} or ${file:…})");
            }
            admin = new Admin(adminName, password);
        }
        return new PlatformConfig(storage, host, port, signing, r, workspace, local, g, oidc(doc.at("/auth/oidc")), ldap(doc.at("/auth/ldap")),
                publicUrl == null ? null : publicUrl.replaceAll("/+$", ""), postgres, admin, tls(doc.at("/http/tls"), env));
    }

    private static Tls tls(JsonNode node, Map<String, String> env) {
        String certificate = env.getOrDefault("MCPGP_PLATFORM_HTTP__TLS__CERTIFICATE", node.path("certificate").asText(null));
        String privateKey = env.getOrDefault("MCPGP_PLATFORM_HTTP__TLS__PRIVATE_KEY", node.path("privateKey").asText(null));
        String keystore = env.getOrDefault("MCPGP_PLATFORM_HTTP__TLS__KEYSTORE", node.path("keystore").asText(null));
        String password = env.getOrDefault("MCPGP_PLATFORM_HTTP__TLS__PASSWORD", node.path("password").asText(null));
        if (certificate == null && privateKey == null && keystore == null) {
            return null;
        }
        if (keystore != null) {
            if (certificate != null || privateKey != null) {
                throw new IllegalArgumentException("http.tls: a keystore, or certificate and privateKey, not both");
            }
            if (password == null || !Secrets.isReference(password)) {
                throw new IllegalArgumentException("http.tls.password is required with a keystore and must be a reference (${env:…} or ${file:…})");
            }
        } else if (certificate == null || privateKey == null) {
            throw new IllegalArgumentException("http.tls needs both certificate and privateKey (paths to PEM files), or a keystore");
        }
        if (Secrets.isReference(privateKey == null ? "" : privateKey)) {
            throw new IllegalArgumentException("http.tls.privateKey is the path to the key file (e.g. /run/secrets/tls_key), not a reference");
        }
        return new Tls(certificate, privateKey, keystore, password);
    }

    private static Postgres postgres(JsonNode node, Map<String, String> env) {
        String url = env.getOrDefault("MCPGP_PLATFORM_STORAGE__URL", node.path("url").asText(null));
        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("storage.url is required for postgres: jdbc:postgresql://host:5432/database");
        }
        String password = env.getOrDefault("MCPGP_PLATFORM_STORAGE__PASSWORD", node.path("password").asText(null));
        if (password != null && !Secrets.isReference(password)) {
            throw new IllegalArgumentException("storage.password must be a reference (${env:…} or ${file:…})");
        }
        int pool = Integer.parseInt(env.getOrDefault("MCPGP_PLATFORM_STORAGE__POOL_SIZE", node.path("poolSize").asText("10")));
        return new Postgres(url, env.getOrDefault("MCPGP_PLATFORM_STORAGE__USER", node.path("user").asText(null)), password,
                env.getOrDefault("MCPGP_PLATFORM_STORAGE__SCHEMA", node.path("schema").asText("mcpgp")), pool);
    }

    private static Oidc oidc(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String issuer = required(node, "issuer", "auth.oidc");
        if (!issuer.startsWith("https://") && !loopback(java.net.URI.create(issuer).getHost())) {
            throw new IllegalArgumentException("auth.oidc.issuer must be https://");
        }
        String secret = node.path("clientSecret").asText(null);
        if (secret != null && !Secrets.isReference(secret)) {
            throw new IllegalArgumentException("auth.oidc.clientSecret must be a reference (${env:…} or ${file:…})");
        }
        List<String> scopes = new java.util.ArrayList<>();
        node.path("scopes").forEach(v -> scopes.add(v.asText()));
        if (scopes.isEmpty()) {
            scopes.addAll(List.of("openid", "profile", "email"));
        }
        if (!scopes.contains("openid")) {
            throw new IllegalArgumentException("auth.oidc.scopes must include openid");
        }
        return new Oidc(issuer, required(node, "clientId", "auth.oidc"), secret, List.copyOf(scopes), node.path("groupsClaim").asText("groups"),
                roles(node, "auth.oidc"), node.path("label").asText("company account"));
    }

    private static Ldap ldap(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String url = required(node, "url", "auth.ldap");
        java.net.URI uri = java.net.URI.create(url);
        boolean startTls = node.path("startTls").asBoolean(false);
        if (!"ldaps".equals(uri.getScheme()) && !("ldap".equals(uri.getScheme()) && (startTls || loopback(uri.getHost())))) {
            // passwords travel to the directory: only encrypted, except to a directory on this host
            throw new IllegalArgumentException("auth.ldap.url must be ldaps://, or ldap:// with startTls: true");
        }
        String bindPassword = node.path("bindPassword").asText(null);
        if (bindPassword != null && !Secrets.isReference(bindPassword)) {
            throw new IllegalArgumentException("auth.ldap.bindPassword must be a reference (${env:…} or ${file:…})");
        }
        String domain = node.at("/activeDirectory/domain").asText(null);
        if (domain == null && node.at("/userSearch/base").isMissingNode()) {
            throw new IllegalArgumentException("auth.ldap needs userSearch (or activeDirectory.domain)");
        }
        return new Ldap(url, startTls, node.path("bindDn").asText(null), bindPassword, node.at("/userSearch/base").asText(null),
                node.at("/userSearch/filter").asText("(uid={0})"), node.at("/groupSearch/base").asText(null),
                node.at("/groupSearch/filter").asText("(member={0})"), domain, roles(node, "auth.ldap"));
    }

    private static Map<String, String> roles(JsonNode node, String where) {
        Map<String, String> roles = new java.util.LinkedHashMap<>();
        node.path("roles").properties().forEach(e -> {
            if (!ro.bitboy.mcpgp.platform.service.Access.ROLES.containsKey(e.getValue().asText())) {
                throw new IllegalArgumentException(where + ".roles." + e.getKey() + ": one of "
                        + String.join(", ", new java.util.TreeSet<>(ro.bitboy.mcpgp.platform.service.Access.ROLES.keySet())));
            }
            roles.put(e.getKey(), e.getValue().asText());
        });
        if (roles.isEmpty()) {
            throw new IllegalArgumentException(where + ".roles: map at least one group to a role; people in no mapped group can't sign in");
        }
        return Map.copyOf(roles);
    }

    private static String required(JsonNode node, String key, String where) {
        String value = node.path(key).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(where + "." + key + " is required");
        }
        return value;
    }

    static boolean loopback(String host) {
        return host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
    }

    /** {@code --config <file>}, else {@code MCPGP_PLATFORM_CONFIG} (the image sets it), else {@code platform.yaml} here if there is one. */
    static Path configFile(String[] args) {
        return configFile(args, System.getenv());
    }

    static Path configFile(String[] args, Map<String, String> env) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--config")) {
                return Path.of(args[i + 1]);
            }
        }
        String fromEnv = env.get("MCPGP_PLATFORM_CONFIG");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv);
        }
        Path local = Path.of("platform.yaml");
        return Files.exists(local) ? local : null;
    }
}
