# Local workspace folder

Stands in for the platform's shared library when using the CLI without the platform:

```
mcpgp build -c orders/orders.mcpgp.yaml --workspace workspace
```

`mcpgp://acme/<kind>/<name>@<version>` resolves to `workspace/<kind>/<name>…`. The orders server
includes the shared notices `mcpgp://acme/notices/acme-policy@1` ([notices/acme-policy.yaml](notices/acme-policy.yaml)).
For this example, `servers/orders-mcp` maps to `../orders` and `contracts/orders@2.0` to
`../orders/contracts/orders-2.0.yaml` via `workspace/mcpgp-workspace.yaml`.
