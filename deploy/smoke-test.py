#!/usr/bin/env python3
"""Checks a running platform end to end (docs/guides/self-hosting.md): health, sign-in, a shared naming
policy and shared notices, the example server, a build, and a tool tried against the mock. It creates data, so run it
against a test installation, not the one your team uses.

    python3 deploy/smoke-test.py http://localhost:8443 admin deploy/secrets/admin_password
    python3 deploy/smoke-test.py https://mcpgp.example.com:9443 admin secrets/admin_password --ca tls/certificate.pem
"""
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request

import ssl

args = [a for a in sys.argv[1:] if a != "--ca"]
ca = sys.argv[sys.argv.index("--ca") + 1] if "--ca" in sys.argv else None
if ca:
    args.remove(ca)
if len(args) != 3:
    sys.exit(__doc__)
base, name, password_file = args[0].rstrip("/"), args[1], args[2]
# --ca: trust this certificate (the platform's own, e.g. self-signed) as well as the system's
context = ssl.create_default_context(cafile=ca) if ca else None
examples = pathlib.Path(__file__).resolve().parent.parent / "examples" / "platform"


def call(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(base + path, method=method, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(request, timeout=60, context=context) as response:
            return response.status, json.loads(response.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"null")


def check(what, ok, detail=""):
    print(("✓ " if ok else "✗ ") + what + (f": {detail}" if detail else ""))
    if not ok:
        sys.exit(1)


status, health = call("GET", "/healthz")
check("health", status == 200, json.dumps(health))
status, session = call("POST", "/api/v1/sessions", {"name": name, "password": pathlib.Path(password_file).read_text().strip()})
check("sign-in as " + name, status == 201, "" if status == 201 else json.dumps(session))
token = session["value"]

naming = (examples / "workspace/naming/acme-standard.yaml").read_text()
status, _ = call("POST", "/api/v1/library/naming?name=acme-standard", {"files": {"acme-standard.yaml": "source: operationId\n"}}, token)
status2, _ = call("POST", "/api/v1/library/naming/acme-standard/versions", {"files": {"acme-standard.yaml": naming}}, token)
check("naming policy acme-standard", status in (201, 409) and status2 == 201, f"{status}, {status2}")
notices = (examples / "workspace/notices/acme-policy.yaml").read_text()
status, _ = call("POST", "/api/v1/library/notices?name=acme-policy", {"files": {"acme-policy.yaml": notices}}, token)
check("shared notices acme-policy", status in (201, 409), str(status))

files = {p.relative_to(examples / "orders").as_posix(): p.read_text() for p in sorted((examples / "orders").rglob("*")) if p.is_file()}
status, server = call("POST", "/api/v1/servers?id=smoke-orders-mcp", {"files": files}, token)
check("server smoke-orders-mcp", status == 201, "" if status == 201 else json.dumps(server))

spec = (examples / "orders/contracts/orders-1.0.json").read_text()
status, plan = call("POST", "/api/v1/servers/from-spec?dryRun=true&id=smoke-from-spec", {"files": {"orders-1.0.json": spec}}, token)
check("a server from a Swagger JSON (dry run)", status == 200 and plan.get("tools", 0) > 0, f'{plan.get("format")}, {plan.get("tools")} tools' if status == 200 else json.dumps(plan))
status, created = call("POST", "/api/v1/servers/from-spec?id=smoke-from-spec", {"files": {"orders-1.0.json": spec}}, token)
check("server smoke-from-spec from the spec", status in (201, 409), "" if status in (201, 409) else json.dumps(created))

status, build = call("POST", "/api/v1/servers/smoke-orders-mcp/builds", {}, token)
check("build started", status == 202, "" if status == 202 else json.dumps(build))
for _ in range(90):
    _, build = call("GET", "/api/v1/builds/" + build["id"], None, token)
    if build["status"] in ("succeeded", "failed"):
        break
    time.sleep(2)
check("build " + build["id"], build["status"] == "succeeded",
      ", ".join(a["name"] for a in build.get("artifacts", [])) if build["status"] == "succeeded" else build.get("error", build["status"]))

status, answer = call("POST", "/api/v1/servers/smoke-orders-mcp/versions/latest/try",
                      {"tool": "orders_customer_list", "arguments": {"instance": "mock", "query": {"accountId": "a-1"}}}, token)
sent = [f'{r["method"]} {r["path"]} → {r["status"]}' for r in answer.get("requests", [])] if status == 200 else []
check("try orders_customer_list against the mock", status == 200 and "error" not in answer and len(sent) == 1, ", ".join(sent) or json.dumps(answer))
status, manifest = call("GET", "/api/v1/servers/smoke-orders-mcp/versions/latest/manifest", None, token)
policy = [t["name"] for t in manifest.get("tools", []) if "Acme policy:" in t.get("description", "")] if status == 200 else []
check("shared notices on the tools", len(policy) > 0, f"{len(policy)} tools carry the Acme policy")
print("All checks passed. To clean up, archive smoke-orders-mcp and smoke-from-spec (their pages in the web UI, or POST /api/v1/servers/<id>/archive).")
