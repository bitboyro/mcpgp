package ro.bitboy.mcpgp.core.diag;

/** Stable diagnostic codes. Documented in docs/spec/definition-format.md §9. */
public final class Codes {

    // file resolution
    public static final String FILE_NOT_FOUND = "FILE_NOT_FOUND";
    public static final String FILE_UNREADABLE = "FILE_UNREADABLE";
    public static final String PATH_OUTSIDE_ROOT = "PATH_OUTSIDE_ROOT";
    public static final String REMOTE_REFERENCE_NOT_ALLOWED = "REMOTE_REFERENCE_NOT_ALLOWED";
    public static final String WORKSPACE_REFERENCE_UNRESOLVED = "WORKSPACE_REFERENCE_UNRESOLVED";
    public static final String REFERENCE_CYCLE = "REFERENCE_CYCLE";
    public static final String INCLUDE_MATCHES_NOTHING = "INCLUDE_MATCHES_NOTHING";

    // schema validation
    public static final String DEFINITION_INVALID = "DEFINITION_INVALID";
    public static final String RULES_INVALID = "RULES_INVALID";
    public static final String NAMING_POLICY_INVALID = "NAMING_POLICY_INVALID";
    public static final String WORKSPACE_INVALID = "WORKSPACE_INVALID";

    // merging and structure
    public static final String MERGE_TYPE_CONFLICT = "MERGE_TYPE_CONFLICT";
    public static final String DUPLICATE_KEY = "DUPLICATE_KEY";
    public static final String DEFAULT_CONTRACT_AMBIGUOUS = "DEFAULT_CONTRACT_AMBIGUOUS";

    // contracts (OpenAPI)
    public static final String SPEC_INVALID = "SPEC_INVALID";
    public static final String SPEC_VERSION_UNSUPPORTED = "SPEC_VERSION_UNSUPPORTED";
    public static final String REF_UNRESOLVED = "REF_UNRESOLVED";
    public static final String OPERATION_ID_DUPLICATE = "OPERATION_ID_DUPLICATE";
    public static final String UNSUPPORTED_FEATURE = "UNSUPPORTED_FEATURE";
    public static final String OVERLAY_INVALID = "OVERLAY_INVALID";
    public static final String OVERLAY_TARGET_MATCHES_NOTHING = "OVERLAY_TARGET_MATCHES_NOTHING";

    private Codes() {
    }
}
