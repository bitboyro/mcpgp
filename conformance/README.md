# Conformance suite

Cases that every compiler and runtime must pass. Format: [docs/spec/conformance.md](../docs/spec/conformance.md).
All files here are validated against the JSON Schemas in CI (`schemas` module).

Expected manifests are produced by the compiler (milestone M2) and reviewed before they are committed;
until then, cases that expect a manifest contain only their inputs and `traffic.yaml`.

The release gate replays every `traffic.yaml` against the packaged server over stdio and HTTP, and runs
generated calls against the fixtures in `fixtures/`: see [runner/ts/README.md](runner/ts/README.md).
