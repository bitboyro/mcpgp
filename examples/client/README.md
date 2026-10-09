# Client-side examples (the operator)

These files are what a customer edits after receiving a package. Every package ships its own
`server.config.schema.json`; the examples follow [docs/spec/runtime-config.md](../../docs/spec/runtime-config.md).

Secrets always go in `.env` (or a keychain), never in the YAML. Copy `.env.example` to `.env`.

| Example | Shows |
|---|---|
| [single-instance](single-instance) | One target server, stdio, secrets from `.env` |
| [multi-instance](multi-instance) | Three target servers, mixed auth, TLS material as path and base64, file inputs |
| [multi-api](multi-api) | Instances grouped per API |
| [env-only](env-only) | The same server configured with environment variables only, list items by index |
| [docker](docker) | Docker Compose |
| [mcp-clients](mcp-clients) | Client registration snippets |
