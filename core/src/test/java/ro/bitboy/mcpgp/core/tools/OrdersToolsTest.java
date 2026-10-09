package ro.bitboy.mcpgp.core.tools;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.resolve.Workspace;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The orders example: which tools exist and what they're called, end to end through the compiler. */
class OrdersToolsTest {

    static final Path PLATFORM = Path.of("..", "examples", "platform").toAbsolutePath().normalize();
    static Compiler.Result orders;
    static Compiler.Result readonly;

    @BeforeAll
    static void compile() {
        Schemas schemas = new Schemas();
        Workspace workspace = Workspace.load(PLATFORM.resolve("workspace/mcpgp-workspace.yaml"), schemas, new Diagnostics());
        Compiler compiler = new Compiler(schemas);
        orders = compiler.compile(PLATFORM.resolve("orders/orders.mcpgp.yaml"), new Compiler.Options(workspace, false));
        readonly = compiler.compile(PLATFORM.resolve("orders-readonly/orders-readonly.mcpgp.yaml"), new Compiler.Options(workspace, false));
    }

    private static ToolPlan tool(Compiler.Result result, String name) {
        return result.tools().stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in " + result.tools().stream().map(ToolPlan::name).toList()));
    }

    @Test
    void compilesWithoutErrorsOrDrift() {
        assertThat(orders.ok()).isTrue();
        // the DELETE rule is real but useless here: access read-write already removes DELETE tools;
        // the jira pack is P1 and not available yet
        assertThat(orders.diagnostics()).filteredOn(d -> d.level() != Diagnostic.Level.INFO)
                .extracting(d -> d.code() + " " + d.location())
                .containsExactly("PACK_UNKNOWN orders.mcpgp.yaml#/packs/jira", "RULE_MATCHES_NOTHING overrides/00-global.yaml#/rules/0");
    }

    @Test
    void selectsAndNamesTheExpectedTools() {
        assertThat(orders.tools()).extracting(ToolPlan::name).containsExactly(
                "orders_account_list",
                "orders_customer_contact_list",
                "orders_customer_create",
                "orders_customer_create_multipart",
                "orders_customer_get",
                "orders_customer_list",
                "orders_customer_update",
                "orders_invoice_document_get",
                "orders_order_refund",
                "orders_order_search",
                "orders_overdue_invoice_list");
    }

    @Test
    void excludedOperationsAreReportedWithAReason() {
        assertThat(orders.diagnostics()).filteredOn(d -> d.code().equals("OPERATION_EXCLUDED"))
                .extracting(Diagnostic::message)
                .containsExactlyInAnyOrder(
                        "orders:GET:/version excluded: excluded by select.exclude",
                        "orders:DELETE:/customers/{id} excluded: access: read-write",
                        "orders:GET:/users excluded: excluded by select.exclude",
                        "orders:GET:/internal/metrics excluded: excluded by select.exclude");
    }

    @Test
    void rulesShapeNamesTitlesAndDescriptions() {
        ToolPlan create = tool(orders, "orders_customer_create");
        assertThat(create.title()).isEqualTo("Create customer");
        assertThat(create.description()).isEqualTo(
                "[Orders] Creates a customer in the given account and returns it with its new id. The account must exist; call orders_account_list first.");
        assertThat(create.mediaType()).isEqualTo("application/json");
        assertThat(create.paramDescriptions()).containsEntry("accountId", "Id of the account the customer belongs to.");
        assertThat(create.provenance().get("name")).extracting(ToolPlan.Provenance::step)
                .containsExactly("source", "normalize", "shape", "rule", "namespace", "lock");

        ToolPlan multipart = tool(orders, "orders_customer_create_multipart");
        assertThat(multipart.mediaType()).isEqualTo("multipart/form-data");
        assertThat(multipart.id()).isEqualTo("orders:POST:/customers#multipart");

        ToolPlan list = tool(orders, "orders_customer_list");
        assertThat(list.description()).isEqualTo("[Orders] Lists customers in an account, newest first. Results are paged; use limit and cursor.");

        ToolPlan get = tool(orders, "orders_customer_get");
        assertThat(get.provenance().get("name")).extracting(ToolPlan.Provenance::value)
                .contains("getCustomerAccountByIdV2", "get customer account", "customer_account_get", "customer_get", "orders_customer_get");
        assertThat(get.variants()).containsOnlyKeys("1.0", "2.0");
        assertThat(get.annotations().readOnlyHint()).isTrue();

        ToolPlan refund = tool(orders, "orders_order_refund");
        assertThat(refund.description()).isEqualTo("[Orders] Refunds a paid order. Only paid orders can be refunded.");
        assertThat(refund.annotations().destructiveHint()).isFalse();

        ToolPlan contacts = tool(orders, "orders_customer_contact_list");
        assertThat(contacts.title()).isEqualTo("Customer contacts (2.0 only)");
        assertThat(contacts.variants()).containsOnlyKeys("2.0");
    }

    @Test
    void variantIsReadOnly() {
        assertThat(readonly.ok()).isTrue();
        assertThat(readonly.tools()).allSatisfy(t -> assertThat(t.annotations().readOnlyHint()).isTrue());
        assertThat(readonly.tools()).extracting(ToolPlan::name).contains("orders_customer_get", "orders_order_search")
                .doesNotContain("orders_customer_create", "orders_order_refund");
    }

    @Test
    void namingUnits() {
        Naming naming = new Naming(Documents_yaml("{ shape: { order: object_verb, number: singular } }"));
        assertThat(naming.words("getTradingPartnerByIDv2")).containsExactly("get", "trading", "partner", "by", "i", "dv2");
        assertThat(naming.words("list-customer.contacts")).containsExactly("list", "customer", "contacts");
        assertThat(Naming.singularize("categories")).isEqualTo("category");
        assertThat(Naming.singularize("addresses")).isEqualTo("address");
        assertThat(Naming.singularize("status")).isEqualTo("status");
        assertThat(naming.limit("Orders-Customer get")).isEqualTo("orders_customer_get");
        String longName = "a".repeat(80);
        assertThat(naming.limit(longName)).hasSize(64).matches("a+_[0-9a-f]{4}");
        assertThat(Selectors.glob("/internal/**").matcher("/internal/a/b").matches()).isTrue();
        assertThat(Selectors.glob("/customers/*").matcher("/customers/{id}/contacts").matches()).isFalse();
        assertThat(TextOps.render("{{summary}} (x)", java.util.Map.of("summary", "Hi"))).isEqualTo("Hi (x)");
    }

    private static com.fasterxml.jackson.databind.JsonNode Documents_yaml(String text) {
        try {
            return ro.bitboy.mcpgp.core.resolve.Documents.YAML.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
