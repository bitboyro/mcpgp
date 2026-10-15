package ro.bitboy.mcpgp.packager;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The container image ({@code docker} target, packaging.md §7.2), as the files that build and run it:
 * {@code docker/Dockerfile} and {@code docker/docker-compose.yml}. The customer builds the image from
 * the package with their own Docker (and their registry's base image when {@code build.image.base}
 * names one), so packaging needs neither a Docker daemon nor the network. The server runs over HTTP as
 * a non-root user, with the config in {@code /config} and its state in {@code /data}.
 */
final class Container {

    static final String DEFAULT_BASE = "eclipse-temurin:21-jre";

    private Container() {
    }

    /** {@code docker/<file>} → content; empty when the build has no HTTP transport (a container only makes sense over HTTP). */
    static Map<String, String> files(JsonNode manifest, JsonNode definition, List<String> warnings) {
        String server = manifest.at("/server/name").asText();
        String version = manifest.at("/server/version").asText().replace('+', '-');
        boolean http = false;
        for (JsonNode transport : manifest.at("/runtime/transports/allowed")) {
            http |= transport.asText().equals("http");
        }
        if (!http) {
            warnings.add("build target docker needs the http transport (runtimeDefaults.transports.allowed); the package has no docker/ files");
            return Map.of();
        }
        JsonNode image = definition.at("/build/image");
        // push, tar and registry are refused by the compiler (NOT_SUPPORTED_YET)
        String base = image.path("base").asText(DEFAULT_BASE);
        String prefix = server.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "_";
        String dockerfile = """
                # %1$s %2$s: the MCP server as a container, over HTTP.
                # Build it from the package's top directory (the one with %1$s.jar):
                #   docker build -f docker/Dockerfile -t %1$s:%2$s .
                # The config needs server.auth (static keys or OAuth): the server refuses to listen beyond loopback without it.
                # Run it with docker/docker-compose.yml, or:
                #   docker run -p 8080:8080 -v "$PWD/config:/config:ro" -v %1$s-data:/data %1$s:%2$s
                FROM %3$s
                RUN groupadd --system --gid 10001 mcp && useradd --system --uid 10001 --gid mcp --no-create-home mcp \\
                    && mkdir -p /app /config /data && chown mcp:mcp /data
                COPY %1$s.jar /app/%1$s.jar
                ENV %4$sSERVER__TRANSPORT=http \\
                    %4$sSERVER__HOST=0.0.0.0 \\
                    %4$sSERVER__PORT=8080 \\
                    %4$sSTORAGE__ROOT=/data
                VOLUME ["/config", "/data"]
                EXPOSE 8080
                USER 10001:10001
                ENTRYPOINT ["java", "-jar", "/app/%1$s.jar", "--config", "/config/%1$s.yaml"]
                """.formatted(server, version, base, prefix);
        String compose = """
                # %1$s %2$s. Put %1$s.yaml (from %1$s.example.yaml) in ./config and its secrets in .env, then:
                #   docker compose -f docker/docker-compose.yml up --build
                services:
                  %1$s:
                    image: %1$s:%2$s
                    build:
                      context: ..
                      dockerfile: docker/Dockerfile
                    ports:
                      - "8080:8080"
                    volumes:
                      - ../config:/config:ro
                      - data:/data
                    env_file:
                      - path: ../.env
                        required: false
                    restart: unless-stopped
                volumes:
                  data:
                """.formatted(server, version);
        return Map.of("docker/Dockerfile", dockerfile, "docker/docker-compose.yml", compose);
    }
}
