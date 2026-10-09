# Contributing

Thanks for helping. The short version:

1. Read [docs/README.md](docs/README.md) and the spec for the area you're touching.
2. Open an issue first (templates in `.github/ISSUE_TEMPLATE`).
3. If behaviour changes: spec + ADR first, see [docs/process/development.md](docs/process/development.md).
4. Branch `<type>/<short-description>`, Conventional Commits, small PRs.
5. Meet the [definition of done](docs/process/development.md#4-definition-of-done).

## Building

Requirements: Java 21+ and Maven 3.9+.

```bash
mvn -B verify
```

This builds every module and validates every file in `examples/` and `conformance/` against the JSON
Schemas in `schemas/src/main/resources/schemas/`. A new file type needs a rule in
`schemas/src/test/java/.../RepositoryFiles.java`.

Never commit secrets, credentials or customer data, including in examples and test fixtures.

By contributing you agree that your contributions are licensed under the [MIT License](LICENSE).
