# Local workspace folder

Stands in for the platform's shared library when using the CLI without the platform:

```
mcpgp build -c orders/orders.mcpgp.yaml --workspace workspace
```

`mcpgp://acme/<kind>/<name>@<version>` resolves to `workspace/<kind>/<name>…`.
For this example, `servers/orders-mcp` maps to `../orders` and `contracts/orders@2.0` to
`../orders/contracts/orders-2.0.yaml` via `workspace/mcpgp-workspace.yaml`.
