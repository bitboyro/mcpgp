package ro.bitboy.mcpgp.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Sign-in with local accounts, passwords, approvals, export and import (spec/platform-api.md §3, §5, §7.1). */
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

    /** {@code {name, password}} → a session token (12 hours). The only endpoint without a token. */
    @PostMapping("/sessions")
    public ResponseEntity<byte[]> signIn(@RequestBody(required = false) byte[] body) {
        if (!config.localAccounts()) {
            throw new ApiException(404, "NOT_FOUND", "local accounts are turned off; sign in with your company account");
        }
        JsonNode json = Json.parse(body);
        Access.IssuedToken token = accounts.signIn(json.path("name").asText(null), json.path("password").asText(null));
        Map<String, Object> result = IdentitiesApi.token(token.record());
        result.put("value", token.value());
        return Json.status(201, result);
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
