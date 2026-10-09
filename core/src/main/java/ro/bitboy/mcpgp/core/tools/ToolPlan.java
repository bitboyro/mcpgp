package ro.bitboy.mcpgp.core.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Operation;

import java.util.List;
import java.util.Map;

/**
 * One tool as decided by selection, naming and rules — everything except its schemas, which are
 * built in the next stage.
 *
 * @param id               stable identity used by the lock file: operation key, plus {@code #<suffix>}
 *                         for the extra tools of a split operation
 * @param mediaType        the request content type this tool is bound to; null when there is no body
 * @param variants         the operation per contract version (definition order)
 * @param paramDescriptions description overrides by parameter name or {@code <group>.<name>}
 * @param parts            multipart part overrides from rules ({@code description}, {@code contentType}, …)
 */
public record ToolPlan(
        String name,
        String id,
        String api,
        String operationKey,
        String operationId,
        List<String> tags,
        String mediaType,
        String title,
        String description,
        Annotations annotations,
        boolean sensitive,
        boolean confirm,
        String expose,
        List<String> accept,
        Map<String, String> paramDescriptions,
        Map<String, ObjectNode> parts,
        Map<String, Operation> variants,
        Map<String, List<Provenance>> provenance) {

    public record Annotations(boolean readOnlyHint, boolean destructiveHint, boolean idempotentHint, boolean openWorldHint) {
    }

    /** One step that shaped a field: {@code step}, the value after it, and where the step came from. */
    public record Provenance(String step, String value, String file, String pointer, String op, String since) {
        static Provenance of(String step, String value) {
            return new Provenance(step, value, null, null, null, null);
        }
    }
}
