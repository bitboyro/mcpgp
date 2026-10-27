# Security policy

## Reporting a vulnerability

Please report it privately, not in a public issue: on GitHub, open the repository's **Security** tab and
choose **Report a vulnerability**. Include what you found, the version, and how to reproduce it.

We'll acknowledge it within 5 working days, keep you updated while we fix it, and credit you in the
advisory unless you'd rather not be named. Please give us time to release a fix before you publish.

This covers everything in this repository: the platform (web UI, REST API, platform MCP server), the CLI,
the compiler, the runtime inside every generated server, and the images we publish. For a server someone
generated with mcpgp, report to whoever delivered it to you; they'll come to us if the cause is in mcpgp.

## Supported versions

| Version | Security fixes |
|---|---|
| 0.4.x (the latest release) | ✅ |
| older | ❌ upgrade to the latest release |

Until 1.0, fixes go into the latest minor version only. A fix in the runtime reaches a generated server
when its package is built again with the fixed version.

## How we check our own code

Every pull request, every push to `main`, and every Monday for code that didn't change
([.github/workflows/security.yml](https://github.com/bitboyro/mcpgp/blob/main/.github/workflows/security.yml)):

- **CodeQL** on the Java and TypeScript code (the `security-extended` queries).
- **Trivy** on the dependencies we ship (from the SBOM), the web UI's lockfile, the Dockerfiles and
  compose files, secrets committed by mistake, and the platform and CLI images.
- **npm audit** on the web UI's runtime dependencies.
- **Dependabot** opens pull requests for security fixes as soon as an advisory is out, and for other
  updates once a week ([.github/dependabot.yml](https://github.com/bitboyro/mcpgp/blob/main/.github/dependabot.yml)).
- **Secret scanning with push protection** blocks keys and tokens before they reach the repository.

A fixable high or critical finding fails the build. The few we accept are listed, each with its reason, in
[.trivyignore.yaml](https://github.com/bitboyro/mcpgp/blob/main/.trivyignore.yaml).

## Checking what you download

Every [release](https://github.com/bitboyro/mcpgp/releases) has:

- `SHA256SUMS` for the files on it, signed with minisign when the release signing key is set;
- `mcpgp-<version>-sbom.cdx.json` and `mcpgp-<version>-web-sbom.cdx.json`: the SBOMs (CycloneDX) of the
  Java and web dependencies we ship;
- the release gate's report (`gate-report.html`), the integration suite this version passed.

The platform and CLI images carry an SBOM and build provenance as attestations:

```bash
docker buildx imagetools inspect docker.io/<account>/mcpgp-platform:<version> --format '{{ json .SBOM }}'
```

## How mcpgp protects the servers it generates

The threat model, the mitigations every generated server has, and who is responsible for what:
[docs/security.md](docs/security.md).
