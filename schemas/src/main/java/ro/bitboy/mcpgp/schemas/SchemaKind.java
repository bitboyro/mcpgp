package ro.bitboy.mcpgp.schemas;

/** The file formats mcpgp defines, each with a JSON Schema under {@code /schemas} on the classpath. */
public enum SchemaKind {
    DEFINITION("definition.schema.json"),
    RULES("rules.schema.json"),
    NAMING_POLICY("naming-policy.schema.json"),
    NAMES_LOCK("names-lock.schema.json"),
    WORKSPACE("workspace.schema.json"),
    RUNTIME_CONFIG("runtime-config.schema.json"),
    ORGANIZATION_POLICY("organization-policy.schema.json"),
    IDENTITY_POLICY("identity-policy.schema.json"),
    MANIFEST("manifest.schema.json"),
    AUDIT_EVENT("audit-event.schema.json"),
    CLI_CONFIG("cli-config.schema.json"),
    NOTICES("notices.schema.json"),
    TOOL_TESTS("tool-tests.schema.json"),
    CONFORMANCE_CASE("conformance-case.schema.json"),
    CONFORMANCE_TRAFFIC("conformance-traffic.schema.json"),
    CONFORMANCE_DIAGNOSTICS("conformance-diagnostics.schema.json");

    private final String fileName;

    SchemaKind(String fileName) {
        this.fileName = fileName;
    }

    public String fileName() {
        return fileName;
    }

    public String id() {
        return Schemas.BASE_URI + fileName;
    }
}
