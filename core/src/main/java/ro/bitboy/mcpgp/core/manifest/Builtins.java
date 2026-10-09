package ro.bitboy.mcpgp.core.manifest;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in tools (tools-contract §7, §15) and the P0 support packs (packaging.md §4.1), as manifest
 * tool entries. Pack tools that act on a target server take {@code instance} in multi-instance builds;
 * the others read local data only.
 */
final class Builtins {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** A tool before naming: its base name and definition (without {@code name}). */
    record Spec(String base, ObjectNode tool) {
    }

    /** A pack: its version and tools. */
    record Pack(String version, List<Spec> tools) {
    }

    private Builtins() {
    }

    // ---------------------------------------------------------------------- built-in tools

    static Spec instancesList(String apiTitle) {
        return builtin("instances_list", "List instances",
                "Lists the configured " + apiTitle + " servers (instances) with their contract version, access and status. "
                        + "Use an instance's name as the `instance` argument of the other tools.",
                readOnly(false), object(), "instances_list");
    }

    /** The tools of a discovery style ({@code search} or {@code catalog}). */
    static List<Spec> discovery(String style, boolean multi) {
        if (style.equals("catalog")) {
            return List.of(
                    builtin("list_operations", "List operations",
                            "Lists the available operations, one line each (name, method, path, summary), optionally filtered by tag or text. Paged.",
                            readOnly(false), object(prop("tag", string("Only operations with this tag.")),
                                    prop("query", string("Text to look for in names and summaries.")),
                                    prop("cursor", string("Cursor from the previous page."))), "list_operations"),
                    builtin("get_operation_schema", "Get operation schema",
                            "Returns the input schema and a response summary of one operation from list_operations.",
                            readOnly(false), object(List.of("name"), prop("name", string("Operation name from list_operations."))),
                            "get_operation_schema"),
                    builtin("invoke_operation", "Invoke operation",
                            "Calls an operation by name with its arguments. The same validation, confirmation and auth apply as for a directly listed tool.",
                            invoke(), withInstance(multi, object(List.of("name", "arguments"),
                                    prop("name", string("Operation name from list_operations.")),
                                    prop("arguments", NODES.objectNode().put("type", "object")
                                            .put("description", "The operation's arguments, as described by get_operation_schema.")))),
                            "invoke_operation"));
        }
        return List.of(
                searchTools(multi),
                describeTool(multi),
                builtin("call_tool", "Call tool",
                        "Calls a tool found with search_tools. The same validation, confirmation and auth apply as for a directly listed tool.",
                        invoke(), withInstance(multi, object(List.of("name", "arguments"),
                                prop("name", string("Tool name from search_tools.")),
                                prop("arguments", NODES.objectNode().put("type", "object")
                                        .put("description", "The tool's arguments, as described by describe_tool.")))),
                        "call_tool"));
    }

    /** {@code plainSearch}: search next to the full list. */
    static List<Spec> plainSearch(boolean multi) {
        return List.of(searchTools(multi), describeTool(multi));
    }

    static List<Spec> toolsets() {
        return List.of(
                builtin("toolsets_list", "List toolsets",
                        "Lists the groups of tools (toolsets) with their description, size and whether they are enabled.",
                        readOnly(false), object(), "toolsets_list"),
                builtin("toolset_enable", "Enable toolset", "Adds a toolset's tools to the tool list of this session.",
                        annotations(false, false, true, false), object(List.of("name"), prop("name", string("Toolset name from toolsets_list."))),
                        "toolset_enable"),
                builtin("toolset_disable", "Disable toolset", "Removes a toolset's tools from the tool list of this session.",
                        annotations(false, false, true, false), object(List.of("name"), prop("name", string("Toolset name from toolsets_list."))),
                        "toolset_disable"));
    }

    private static Spec searchTools(boolean multi) {
        ObjectNode limit = NODES.objectNode().put("type", "integer").put("minimum", 1).put("maximum", 50).put("default", 10)
                .put("description", "Maximum number of results.");
        return builtin("search_tools", "Search tools",
                "Finds tools by what they do. Returns one line per tool (name, title, summary, read-only), best matches first.",
                readOnly(false), withInstance(multi, object(List.of("query"),
                        prop("query", string("What you want to do, in a few words.")), prop("limit", limit))),
                "search_tools");
    }

    private static Spec describeTool(boolean multi) {
        return builtin("describe_tool", "Describe tool",
                "Returns a tool's full definition: description, input schema and annotations.",
                readOnly(false), withInstance(multi, object(List.of("name"), prop("name", string("Tool name from search_tools.")))),
                "describe_tool");
    }

    // ---------------------------------------------------------------------- packs

    /** The P0 packs; null for a pack this version doesn't provide. */
    static Pack pack(String id, boolean multi) {
        return switch (id) {
            case "schema" -> new Pack("1", List.of(
                    pack(id, "describe_schema", "Describe schema",
                            "Returns the full input schema of a tool, summarized one level deep. Use `part` to drill into one named definition and `variant` to narrow a oneOf. Reads bundled data only.",
                            readOnly(false), object(List.of("tool"), prop("tool", string("Tool name.")),
                                    prop("part", string("Name of a definition to show in full.")),
                                    prop("variant", string("oneOf variant to narrow to."))))));
            case "audit" -> new Pack("1", List.of(
                    pack(id, "audit_list", "List audit events",
                            "Lists audit events of this server, newest first, optionally filtered by tool, instance, outcome and time. Paged.",
                            readOnly(false), object(prop("tool", string("Only events of this tool.")),
                                    prop("instance", string("Only events against this instance.")),
                                    prop("outcome", NODES.objectNode().put("type", "string").<ObjectNode>set("enum",
                                            NODES.arrayNode().add("ok").add("error").add("denied"))),
                                    prop("since", NODES.objectNode().put("type", "string").put("format", "date-time")),
                                    prop("until", NODES.objectNode().put("type", "string").put("format", "date-time")),
                                    prop("limit", NODES.objectNode().put("type", "integer").put("minimum", 1).put("maximum", 200).put("default", 50)),
                                    prop("cursor", string("Cursor from the previous page."))))));
            case "diagnostics" -> new Pack("1", List.of(
                    pack(id, "tls_certificate_fetch", "Fetch TLS certificate",
                            "Connects to the instance's host and returns its TLS certificate chain (PEM, subject, issuer, validity, SHA-256) with a warning when the hostname doesn't match. Read-only.",
                            annotations(true, false, true, true), withInstance(multi, object())),
                    pack(id, "connectivity_check", "Check connectivity",
                            "Checks that the instance is reachable and that its sign-in works, and explains what failed.",
                            annotations(true, false, true, true), withInstance(multi, object()))));
            case "health" -> new Pack("1", List.of());
            case "recording" -> new Pack("1", List.of(
                    pack(id, "recording_start", "Start recording",
                            "Starts a named recording of upstream requests and responses (redacted) for this session.",
                            annotations(false, false, false, false), object(List.of("description"),
                                    prop("description", string("What is being recorded and why.")))),
                    pack(id, "recording_stop", "Stop recording", "Stops the current recording and stores its findings.",
                            annotations(false, false, false, false), object(prop("findings", string("What the recording showed.")))),
                    pack(id, "recording_list", "List recorded calls", "Lists the calls captured in a recording. Paged.",
                            readOnly(false), object(prop("session", string("Recording session id; the current one when omitted.")),
                                    prop("cursor", string("Cursor from the previous page.")))),
                    pack(id, "recording_sessions", "List recordings", "Lists recording sessions, newest first. Paged.",
                            readOnly(false), object(prop("cursor", string("Cursor from the previous page."))))));
            case "introspection" -> new Pack("1", List.of(
                    pack(id, "explain_tool", "Explain tool",
                            "Explains where a tool's name, title and description come from (contract, rules, lock file).",
                            readOnly(false), object(List.of("tool"), prop("tool", string("Tool name.")))),
                    pack(id, "manifest_info", "Manifest info",
                            "Returns this server's build information: version, contracts, exposure and enabled packs.",
                            readOnly(false), object())));
            default -> null;
        };
    }

    // ---------------------------------------------------------------------- helpers

    private static Spec builtin(String base, String title, String description, ObjectNode annotations, ObjectNode input, String handler) {
        ObjectNode tool = tool("builtin", title, description, annotations, input);
        tool.put("handler", handler);
        return new Spec(base, tool);
    }

    private static Spec pack(String pack, String base, String title, String description, ObjectNode annotations, ObjectNode input) {
        ObjectNode tool = tool("pack", title, description, annotations, input);
        tool.put("pack", pack);
        return new Spec(base, tool);
    }

    private static ObjectNode tool(String kind, String title, String description, ObjectNode annotations, ObjectNode input) {
        ObjectNode tool = NODES.objectNode().put("kind", kind).put("title", title).put("description", description);
        tool.set("annotations", annotations);
        tool.put("sensitive", false);
        tool.put("confirm", false);
        tool.set("inputSchema", input);
        return tool;
    }

    private static ObjectNode readOnly(boolean openWorld) {
        return annotations(true, false, true, openWorld);
    }

    /** Calls any tool, so it is conservatively marked destructive; the called tool's own checks still apply. */
    private static ObjectNode invoke() {
        return annotations(false, true, false, true);
    }

    private static ObjectNode annotations(boolean readOnly, boolean destructive, boolean idempotent, boolean openWorld) {
        return NODES.objectNode()
                .put("readOnlyHint", readOnly)
                .put("destructiveHint", destructive)
                .put("idempotentHint", idempotent)
                .put("openWorldHint", openWorld);
    }

    private static Map.Entry<String, ObjectNode> prop(String name, ObjectNode schema) {
        return Map.entry(name, schema);
    }

    private static ObjectNode string(String description) {
        return NODES.objectNode().put("type", "string").put("description", description);
    }

    @SafeVarargs
    private static ObjectNode object(Map.Entry<String, ObjectNode>... properties) {
        return object(List.of(), properties);
    }

    @SafeVarargs
    private static ObjectNode object(List<String> required, Map.Entry<String, ObjectNode>... properties) {
        Map<String, ObjectNode> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, ObjectNode> p : properties) {
            ordered.put(p.getKey(), p.getValue());
        }
        ObjectNode schema = NODES.objectNode().put("type", "object");
        ObjectNode props = NODES.objectNode();
        ordered.forEach(props::set);
        schema.set("properties", props);
        if (!required.isEmpty()) {
            ArrayNode r = NODES.arrayNode();
            required.forEach(r::add);
            schema.set("required", r);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    /** Adds the required {@code instance} parameter (filled in at startup) in multi-instance builds. */
    private static ObjectNode withInstance(boolean multi, ObjectNode schema) {
        if (!multi) {
            return schema;
        }
        ObjectNode props = NODES.objectNode();
        props.set("instance", NODES.objectNode().put("type", "string")
                .put("description", "Target server. Filled in at startup from the runtime config.")
                .<ObjectNode>set("enum", NODES.arrayNode())
                .put("x-mcpgp-instanceEnum", true));
        schema.path("properties").fields().forEachRemaining(f -> props.set(f.getKey(), f.getValue()));
        schema.set("properties", props);
        List<String> required = new ArrayList<>(List.of("instance"));
        schema.path("required").forEach(r -> required.add(r.asText()));
        ArrayNode r = NODES.arrayNode();
        required.forEach(r::add);
        schema.set("required", r);
        return schema;
    }
}
