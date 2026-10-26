# syntax=docker/dockerfile:1.7
# The platform image (docs/guides/self-hosting.md). Build from the repository root:
#   docker build -f deploy/Dockerfile -t mcpgp-platform .

# ---- build: the platform jar, with the web UI and the runtime it packages servers with.
# The jar is the same on every architecture, so this stage runs natively on the builder (no emulation
# for multi-architecture images); only the run stage below is built per architecture.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
# the commit, recorded in the jar and shown in the UI's About panel (.git isn't copied into the build)
ARG MCPGP_COMMIT=unknown
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl platform -am package -DskipTests -Dmcpgp.commit="$MCPGP_COMMIT" \
 && cp platform/target/mcpgp-platform-*-exec.jar /mcpgp-platform.jar

# ---- run
FROM eclipse-temurin:21-jre-noble
# the image's own name, which `setup` writes into .env (the publish workflow passes the pushed name)
ARG MCPGP_IMAGE=mcpgp-platform:local
LABEL org.opencontainers.image.title="mcpgp platform" \
      org.opencontainers.image.source="https://github.com/bitboyro/mcpgp"
RUN groupadd --system --gid 10001 mcpgp \
 && useradd --system --uid 10001 --gid mcpgp --home-dir /var/lib/mcpgp --shell /usr/sbin/nologin mcpgp \
 && mkdir -p /var/lib/mcpgp /etc/mcpgp /opt/mcpgp \
 && chown mcpgp:mcpgp /var/lib/mcpgp
COPY --from=build /mcpgp-platform.jar /opt/mcpgp/mcpgp-platform.jar
COPY deploy/platform.default.yaml /etc/mcpgp/platform.yaml
# the specs and guides that messages and generated files refer to, at /opt/mcpgp/docs
COPY docs/spec /opt/mcpgp/docs/spec
COPY docs/guides /opt/mcpgp/docs/guides
COPY docs/decisions /opt/mcpgp/docs/decisions
COPY schemas/src/main/resources/schemas /opt/mcpgp/schemas/src/main/resources/schemas
COPY docs/concepts.md docs/security.md docs/deployment.md /opt/mcpgp/docs/
# listen on every interface inside the container; the data directory is the volume
ENV MCPGP_PLATFORM_IMAGE=$MCPGP_IMAGE \
    MCPGP_PLATFORM_CONFIG=/etc/mcpgp/platform.yaml \
    MCPGP_PLATFORM_HTTP__HOST=0.0.0.0 \
    MCPGP_PLATFORM_HTTP__PORT=8443 \
    MCPGP_PLATFORM_STORAGE__ROOT=/var/lib/mcpgp
USER 10001:10001
WORKDIR /var/lib/mcpgp
VOLUME /var/lib/mcpgp
EXPOSE 8443
# plain HTTP, or HTTPS when http.tls is set (the platform's own certificate, often self-signed)
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8443/healthz || curl -fsSk https://127.0.0.1:8443/healthz || exit 1
# the platform's heap: half the container's memory; Try it servers run next to it with 256 MB each
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/opt/mcpgp/mcpgp-platform.jar"]
# no command: run; `setup`, `check`, `init`, `copy-store` as commands (the config comes from MCPGP_PLATFORM_CONFIG)
