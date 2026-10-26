# syntax=docker/dockerfile:1.7
# The CLI image (spec/cli.md §1), for CI: `mcpgp` on the PATH, git for build times and checkouts.
#   docker build -f deploy/cli.Dockerfile -t mcpgp-cli .
#   docker run --rm -v "$PWD":/work mcpgp-cli validate --all

# ---- build: the CLI jar (the same on every architecture, so built natively)
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl cli -am package -DskipTests \
 && cp cli/target/mcpgp-cli-*-exec.jar /mcpgp-cli.jar

# ---- run
FROM eclipse-temurin:21-jre-noble
LABEL org.opencontainers.image.title="mcpgp CLI" \
      org.opencontainers.image.source="https://github.com/bitboyro/mcpgp"
RUN apt-get update -q && apt-get install -y -q --no-install-recommends git \
 && rm -rf /var/lib/apt/lists/* \
 && git config --system --add safe.directory '*'
COPY --from=build /mcpgp-cli.jar /opt/mcpgp/lib/mcpgp-cli.jar
COPY cli/src/dist/bin/mcpgp /opt/mcpgp/bin/mcpgp
# the specs and guides that messages and generated files refer to, at /opt/mcpgp/docs
COPY docs/spec /opt/mcpgp/docs/spec
COPY docs/guides /opt/mcpgp/docs/guides
COPY docs/decisions /opt/mcpgp/docs/decisions
COPY schemas/src/main/resources/schemas /opt/mcpgp/schemas/src/main/resources/schemas
COPY docs/concepts.md docs/security.md docs/deployment.md /opt/mcpgp/docs/
COPY SECURITY.md /opt/mcpgp/
RUN chmod 755 /opt/mcpgp/bin/mcpgp && ln -s /opt/mcpgp/bin/mcpgp /usr/local/bin/mcpgp
# CI runners mount the checkout and run steps as root; locally, pass --user "$(id -u):$(id -g)"
WORKDIR /work
ENTRYPOINT ["mcpgp"]
CMD ["--help"]
