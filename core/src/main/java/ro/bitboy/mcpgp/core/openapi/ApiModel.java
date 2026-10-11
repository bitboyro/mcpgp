package ro.bitboy.mcpgp.core.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * The internal, version-independent model of one contract (one OpenAPI document version). Swagger 2.0,
 * OpenAPI 3.0 and 3.1 all load into this shape. Schemas are kept as JSON trees; references to shared
 * schemas use {@code #/components/schemas/<Name>} for every source format.
 */
public final class ApiModel {

    private ApiModel() {
    }

    public enum Format {
        SWAGGER_2_0("swagger-2.0"), OPENAPI_3_0("openapi-3.0"), OPENAPI_3_1("openapi-3.1");

        private final String id;

        Format(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /**
     * @param basePath   path prefix from {@code servers[0].url} (3.x) or {@code basePath} (2.0); "" if none
     * @param schemas    shared schemas by name ({@code components.schemas} / {@code definitions})
     * @param security   the document's default security requirements
     */
    public record Contract(
            String api,
            String version,
            Format format,
            String infoVersion,
            String basePath,
            List<Operation> operations,
            Map<String, SecurityScheme> securitySchemes,
            List<List<SecurityRequirement>> security,
            Map<String, ObjectNode> schemas,
            String file) {
    }

    /**
     * @param key        stable identity: {@code <api>:<METHOD>:<path>}
     * @param pointer    JSON pointer of the operation in the source document
     * @param security   the operation's requirements (alternatives of AND-sets); null = inherit the document's
     */
    public record Operation(
            String key,
            String method,
            String path,
            String operationId,
            List<String> tags,
            String summary,
            String description,
            boolean deprecated,
            List<Parameter> parameters,
            RequestBody requestBody,
            List<Response> responses,
            List<List<SecurityRequirement>> security,
            Map<String, JsonNode> extensions,
            String pointer) {
    }

    public record Parameter(
            String name,
            String in,
            boolean required,
            String description,
            JsonNode schema,
            String style,
            boolean explode,
            boolean deprecated,
            String pointer) {
    }

    /** All media types an operation accepts, in spec order. */
    public record RequestBody(boolean required, String description, List<MediaType> content) {
    }

    /**
     * @param encoding for multipart and form bodies: allowed content types per part name
     * @param example  the media type's example ({@code example}, or the first of {@code examples}); null if none
     */
    public record MediaType(String name, JsonNode schema, Map<String, List<String>> encoding, JsonNode example) {
        public MediaType(String name, JsonNode schema, Map<String, List<String>> encoding) {
            this(name, schema, encoding, null);
        }
    }

    /** One response status with its media types ({@code status} is "200", "2XX" or "default"). */
    public record Response(String status, String description, List<MediaType> content) {
    }

    /**
     * @param type   {@code http}, {@code apiKey}, {@code oauth2}, {@code openIdConnect}, {@code mutualTLS}
     * @param scheme for http: {@code basic}, {@code bearer}, …
     * @param in     for apiKey: {@code header}, {@code query}, {@code cookie}
     * @param scopes for oauth2: every scope declared by any flow
     */
    public record SecurityScheme(String name, String type, String scheme, String in, String parameterName,
                                 List<String> scopes, String tokenUrl) {
    }

    public record SecurityRequirement(String scheme, List<String> scopes) {
    }
}
