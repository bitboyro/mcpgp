package ro.bitboy.mcpgp.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mcpgp.platform.service.Access;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Identities, tokens and the activity log (spec/platform-api.md §3, §6). */
@RestController
@RequestMapping("/api/v1")
public class IdentitiesApi {

    private final Store store;
    private final Access access;
    private final ro.bitboy.mcpgp.platform.service.Accounts accounts;

    public IdentitiesApi(Store store, Access access, ro.bitboy.mcpgp.platform.service.Accounts accounts) {
        this.store = store;
        this.access = access;
        this.accounts = accounts;
    }

    @GetMapping("/me")
    public ResponseEntity<byte[]> me(HttpServletRequest request) {
        Access.Principal who = ServersApi.who(request);
        Map<String, Object> body = identity(who.identity());
        body.put("tokenScopes", who.scopes());
        if (accounts.mustChange(who.identity().id())) {
            body.put("mustChangePassword", true);
        }
        if (who.identity().kind().equals("person")) {
            body.put("password", accounts.hasPassword(who.identity())); // a local password they can change
        }
        return Json.ok(body);
    }

    @GetMapping("/identities")
    public ResponseEntity<byte[]> identities(HttpServletRequest request) {
        ServersApi.who(request).require("admin");
        List<Map<String, Object>> items = new ArrayList<>();
        store.identities().forEach(i -> {
            Map<String, Object> item = identity(i);
            if (i.kind().equals("person")) {
                item.put("password", accounts.hasPassword(i));
                if (accounts.mustChange(i.id())) {
                    item.put("mustChangePassword", true);
                }
                accounts.lockedUntil(i).ifPresent(until -> item.put("lockedUntil", until.toString()));
            }
            items.add(item);
        });
        return Json.ok(Map.of("items", items));
    }

    /** {@code {kind, name, role | scopes}}. */
    @PostMapping("/identities")
    public ResponseEntity<byte[]> createIdentity(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        Access.Principal who = ServersApi.who(request);
        who.require("admin");
        JsonNode json = Json.parse(body);
        Set<String> scopes = scopes(json);
        if (json.hasNonNull("password") && !json.path("kind").asText("").equals("person")) {
            throw ApiException.validation("only people have passwords; agents and pipelines use tokens");
        }
        if (json.hasNonNull("password") && json.get("password").asText().length() < 12) {
            throw ApiException.validation("the password needs at least 12 characters");
        }
        Model.Identity identity = access.createIdentity(json.path("kind").asText(""), json.path("name").asText(null), scopes,
                who.identity().id());
        if (json.hasNonNull("password")) {
            accounts.setPassword(identity, json.get("password").asText());
        }
        record(who, "identity.create", identity.name(), Map.of("kind", identity.kind(), "scopes", scopes));
        return Json.status(201, identity(identity));
    }

    /** {@code {identity?, scopes?, label?, expiresInDays?}}; the value is shown once. */
    @PostMapping("/tokens")
    public ResponseEntity<byte[]> createToken(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        Access.Principal who = ServersApi.who(request);
        JsonNode json = Json.parse(body);
        Model.Identity identity = who.identity();
        if (json.hasNonNull("identity") && !json.get("identity").asText().equals(identity.id())) {
            who.require("admin");
            identity = store.identities().stream().filter(i -> i.id().equals(json.get("identity").asText())).findFirst()
                    .orElseThrow(() -> ApiException.notFound("identity"));
        }
        Set<String> scopes = json.has("scopes") ? scopes(json) : null;
        if (!who.has("admin") && scopes != null && !who.scopes().containsAll(scopes)) {
            throw ApiException.validation("a token can't have more scopes than the token that creates it");
        }
        int days = json.path("expiresInDays").asInt(90);
        if (days < 1 || days > 730) {
            throw ApiException.validation("expiresInDays: 1–730");
        }
        Access.IssuedToken issued = access.issue(identity, scopes == null && !who.has("admin") ? who.scopes() : scopes,
                json.path("label").asText(null), Instant.now().plus(days, ChronoUnit.DAYS));
        record(who, "token.create", identity.name(), Map.of("token", issued.record().id(), "scopes", issued.record().scopes()));
        Map<String, Object> result = token(issued.record());
        result.put("value", issued.value());
        result.put("note", "shown once; store it as a secret");
        return Json.status(201, result);
    }

    @GetMapping("/tokens")
    public ResponseEntity<byte[]> tokens(HttpServletRequest request) {
        Access.Principal who = ServersApi.who(request);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Model.Token t : store.tokens()) {
            if (who.has("admin") || t.identity().equals(who.identity().id())) {
                items.add(token(t));
            }
        }
        return Json.ok(Map.of("items", items));
    }

    @DeleteMapping("/tokens/{id}")
    public ResponseEntity<byte[]> revoke(HttpServletRequest request, @PathVariable String id) {
        Access.Principal who = ServersApi.who(request);
        Model.Token token = store.tokens().stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("token"));
        if (!token.identity().equals(who.identity().id())) {
            who.require("admin");
        }
        store.saveToken(new Model.Token(token.id(), token.identity(), token.hash(), token.scopes(), token.label(), token.created(),
                token.expires(), true));
        record(who, "token.revoke", token.id(), Map.of());
        return Json.ok(token(store.tokens().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow()));
    }

    @GetMapping("/activity")
    public ResponseEntity<byte[]> activity(HttpServletRequest request, @RequestParam(required = false) String target,
                                           @RequestParam(required = false) String server, @RequestParam(required = false) String actor, @RequestParam(required = false) String action,
                                           @RequestParam(defaultValue = "50") int limit) {
        ServersApi.who(request).require("servers:read");
        // server: the server's own entries and those of its builds (which name it in their details)
        List<Model.Activity> items = store.activity(new Store.ActivityQuery(target, server, actor, action), Math.max(1, Math.min(200, limit)));
        return Json.ok(Map.of("items", items));
    }

    private static Set<String> scopes(JsonNode json) {
        Set<String> scopes = new LinkedHashSet<>();
        if (json.hasNonNull("role")) {
            Set<String> role = Access.ROLES.get(json.get("role").asText());
            if (role == null) {
                throw ApiException.validation("role: " + String.join(", ", Access.ROLES.keySet()));
            }
            scopes.addAll(role);
        }
        json.path("scopes").forEach(s -> scopes.add(s.asText()));
        return scopes;
    }

    private void record(Access.Principal who, String action, String target, Map<String, Object> details) {
        store.append(new Model.Activity(Instant.now().toString(), who.identity().name(), who.identity().kind(), action, target, "ok", details));
    }

    static Map<String, Object> identity(Model.Identity i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("kind", i.kind());
        m.put("name", i.name());
        m.put("scopes", i.scopes());
        m.put("created", i.created());
        m.put("disabled", i.disabled());
        if (i.external() != null) {
            // oidc or ldap; the account itself (a DN, a subject) stays out of lists
            m.put("signsInWith", i.external().substring(0, i.external().indexOf(':')));
        }
        if (ro.bitboy.mcpgp.platform.service.SignIns.DIRECTORY.equals(i.owner())) {
            m.put("rolesFromGroups", true);
        }
        return m;
    }

    static Map<String, Object> token(Model.Token t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("identity", t.identity());
        m.put("scopes", t.scopes());
        if (t.label() != null) {
            m.put("label", t.label());
        }
        m.put("created", t.created());
        m.put("expires", t.expires());
        m.put("revoked", t.revoked());
        return m;
    }
}
