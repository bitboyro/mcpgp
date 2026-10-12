package ro.bitboy.mcpgp.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mcpgp.platform.PlatformConfig;
import ro.bitboy.mcpgp.platform.service.Access;
import ro.bitboy.mcpgp.platform.service.Accounts;
import ro.bitboy.mcpgp.platform.service.Guardrails;
import ro.bitboy.mcpgp.platform.service.Transfer;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sign-in with local accounts, sign-out, sign-in options and settings, passwords, approvals, export and import (spec/platform-api.md §3, §5, §7.1). */
@RestController
@RequestMapping("/api/v1")
public class AccountsApi {

    private final Store store;
    private final Accounts accounts;
    private final Guardrails guardrails;
    private final Transfer transfer;
    private final PlatformConfig config;

    public AccountsApi(Store store, Accounts accounts, Guardrails guardrails, Transfer transfer, PlatformConfig config) {
        this.store = store;
        this.accounts = accounts;
        this.guardrails = guardrails;
        this.transfer = transfer;
        this.config = config;
    }

    /**
     * {@code {name, password}} → a session token (12 hours). The web UI (header {@code X-Mcpgp-Client: web})
     * gets it as an HttpOnly cookie instead of in the body, so its scripts never hold the token.
     */
    @PostMapping("/sessions")
    public ResponseEntity<byte[]> signIn(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        if (!config.localAccounts() && config.ldap() == null) {
            throw new ApiException(404, "NOT_FOUND", "sign-in with a name and password is turned off; sign in with your company account");
        }
        String origin = request.getHeader("Origin");
        if (AuthFilter.isWeb(request) && config.publicUrl() != null && config.publicUrl().startsWith("https://")
                && origin != null && origin.startsWith("http://")) {
            // the session cookie is Secure for an https:// platform: a browser on plain HTTP would drop it
            throw new ApiException(409, "WRONG_ADDRESS", "This platform's address is " + config.publicUrl() + ", and this page is "
                    + origin + ": over plain HTTP the browser can't keep you signed in. Open " + config.publicUrl() + " instead.",
                    Map.of("publicUrl", config.publicUrl()));
        }
        JsonNode json = Json.parse(body);
        Access.IssuedToken token = accounts.signIn(json.path("name").asText(null), json.path("password").asText(null));
        Map<String, Object> result = IdentitiesApi.token(token.record());
        if (AuthFilter.isWeb(request)) {
            return ResponseEntity.status(201).header(HttpHeaders.SET_COOKIE, sessionCookie(request, token.value(), Accounts.SESSION, config.publicUrl()).toString())
                    .contentType(MediaType.APPLICATION_JSON).body(Json.status(201, result).getBody());
        }
        result.put("value", token.value());
        return Json.status(201, result);
    }

    /** Signs out: revokes the session token in use and clears the web UI's cookie. API tokens are revoked with DELETE /tokens/{id}. */
    @DeleteMapping("/sessions/current")
    public ResponseEntity<byte[]> signOut(HttpServletRequest request) {
        Access.Principal who = ServersApi.who(request);
        Model.Token token = store.tokens().stream().filter(t -> t.id().equals(who.token())).findFirst().orElseThrow();
        if (!"session".equals(token.label())) {
            throw new ApiException(409, "CONFLICT", "this is an API token, not a session; revoke it with DELETE /tokens/" + token.id());
        }
        store.saveToken(new Model.Token(token.id(), token.identity(), token.hash(), token.scopes(), token.label(), token.created(),
                token.expires(), true));
        store.append(new Model.Activity(Instant.now().toString(), who.identity().name(), who.identity().kind(), "signout",
                who.identity().name(), "ok", Map.of("token", token.id())));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, sessionCookie(request, "", Duration.ZERO, config.publicUrl()).toString())
                .contentType(MediaType.APPLICATION_JSON).body(Json.ok(Map.of("signedOut", true)).getBody());
    }

    /** How people can sign in; needed before signing in, so it has no token and tells nothing else. */
    @GetMapping("/platform")
    public ResponseEntity<byte[]> platform() {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("auth", authOptions(config));
        answer.put("publicUrl", config.publicUrl());
        return Json.ok(answer);
    }

    /** The installation's settings, as far as builders need them (spec/platform-api.md §7). */
    @GetMapping("/settings")
    public ResponseEntity<byte[]> settings(HttpServletRequest request) {
        ServersApi.who(request).require("servers:read");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace", config.workspace());
        body.put("auth", authOptions(config));
        body.put("signing", Map.of("configured", config.signingKey() != null && !config.signingKey().isBlank()));
        body.put("retention", config.retention());
        body.put("guardrails", config.guardrails());
        return Json.ok(body);
    }

    /** How people sign in: a name and password (local accounts, the directory, or both), and company SSO. */
    static Map<String, Object> authOptions(PlatformConfig config) {
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("local", config.localAccounts());
        auth.put("ldap", config.ldap() != null);
        auth.put("oidc", config.oidc() != null);
        if (config.oidc() != null) {
            auth.put("oidcLabel", config.oidc().label());
        }
        return auth;
    }

    /** Secure when the request came over https, or when the platform's public address is https (behind a TLS proxy). */
    public static ResponseCookie sessionCookie(HttpServletRequest request, String value, Duration maxAge, String publicUrl) {
        boolean https = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"))
                || (publicUrl != null && publicUrl.startsWith("https://"));
        return ResponseCookie.from(AuthFilter.COOKIE, value).httpOnly(true).secure(https).sameSite("Strict").path("/").maxAge(maxAge).build();
    }

    /** {@code {password, currentPassword?}}: admins set anyone's; people change their own with the current one. */
    @PutMapping("/identities/{id}/password")
    public ResponseEntity<byte[]> password(HttpServletRequest request, @PathVariable String id, @RequestBody(required = false) byte[] body) {
        Access.Principal who = ServersApi.who(request);
        JsonNode json = Json.parse(body);
        Model.Identity identity = store.identities().stream().filter(i -> i.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("identity"));
        if (!who.has("admin")) {
            if (!identity.id().equals(who.identity().id())) {
                throw ApiException.forbidden("admin");
            }
            if (accounts.hasPassword(identity) && !accounts.check(identity, json.path("currentPassword").asText(""))) {
                throw new ApiException(403, "FORBIDDEN", "the current password is wrong");
            }
        }
        accounts.setPassword(identity, json.path("password").asText(null));
        store.append(new Model.Activity(Instant.now().toString(), who.identity().name(), who.identity().kind(), "password.set",
                identity.name(), "ok", Map.of()));
        return Json.ok(Map.of("identity", identity.id(), "password", "set"));
    }

    @GetMapping("/approvals")
    public ResponseEntity<byte[]> approvals(HttpServletRequest request, @RequestParam(defaultValue = "pending") String status) {
        ServersApi.who(request).require("servers:read");
        List<Model.Approval> items = new ArrayList<>();
        store.approvals().stream().filter(a -> status.equals("all") || a.status().equals(status)).forEach(items::add);
        return Json.ok(Map.of("items", items));
    }

    @PostMapping("/approvals/{id}/approve")
    public ResponseEntity<byte[]> approve(HttpServletRequest request, @PathVariable String id, @RequestBody(required = false) byte[] body) {
        Access.Principal who = ServersApi.who(request);
        who.require("approvals:decide");
        return Json.ok(guardrails.decide(id, true, Json.parse(body).path("comment").asText(null), who));
    }

    @PostMapping("/approvals/{id}/reject")
    public ResponseEntity<byte[]> reject(HttpServletRequest request, @PathVariable String id, @RequestBody(required = false) byte[] body) {
        Access.Principal who = ServersApi.who(request);
        who.require("approvals:decide");
        return Json.ok(guardrails.decide(id, false, Json.parse(body).path("comment").asText(null), who));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(HttpServletRequest request, @RequestParam(defaultValue = "false") boolean artifacts) {
        Access.Principal who = ServersApi.who(request);
        who.require("admin");
        byte[] zip = transfer.export(artifacts);
        store.append(new Model.Activity(Instant.now().toString(), who.identity().name(), who.identity().kind(), "platform.export", "store",
                "ok", Map.of("artifacts", artifacts, "bytes", zip.length)));
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"mcpgp-export.zip\"")
                .contentType(MediaType.parseMediaType("application/zip")).body(zip);
    }

    @PostMapping("/import")
    public ResponseEntity<byte[]> importAll(HttpServletRequest request, @RequestHeader(value = "Content-Type", required = false) String type,
                                            @RequestBody(required = false) byte[] body) {
        Access.Principal who = ServersApi.who(request);
        who.require("admin");
        Map<String, Integer> counts = transfer.importAll(body == null ? new byte[0] : body);
        store.append(new Model.Activity(Instant.now().toString(), who.identity().name(), who.identity().kind(), "platform.import", "store",
                "ok", Map.copyOf(counts)));
        return Json.ok(counts);
    }
}
