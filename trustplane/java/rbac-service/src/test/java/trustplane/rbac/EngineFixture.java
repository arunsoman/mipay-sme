package trustplane.rbac;

import org.springframework.jdbc.core.JdbcTemplate;

import trustplane.rbac.core.Policy;

import trustplane.rbac.core.Verbs;

import java.util.Arrays;
import java.util.List;

/**
 * TEST-ONLY role fixture for the decision-engine suite (RbacGridTest), ported from the retired
 * grid.engine-test.yaml when the seed was removed (2026-11-13): production and boot never load
 * this - the RBAC boots EMPTY and roles are created through the APIs. The fixture reproduces the
 * pre-retirement grid (many standard roles + rows) so the engine tests (SoD, ceilings, four-eyes,
 * holds) keep their realistic data. Rows are registered as the backend's boot registration would;
 * org_admin and org_viewer carry the legacy {@code seeded} flag so the uat write-lock rule for
 * rows created before the redesign still has something to bite on.
 */
final class EngineFixture {

    private EngineFixture() {}

    private static final String S = "mipay";

    /** resource id -> {used verbs (pipe), tenant_types (or null)} */
    private static final String[] ROWS = {
            "admin.access|read,administer|null",
            "admin.aml_alert|read|null",
            "admin.api_client|read,create,administer|null",
            "admin.bootstrap|administer|null",
            "admin.config|read,modify|null",
            "admin.connector|read,modify,execute,administer|null",
            "admin.dsar|read,create,export|null",
            "admin.platform_ops|read,execute,administer|null",
            "admin.sar|read,create,submit|null",
            "admin.simulation|read,simulate|null",
            "kyc.application|read,modify,delete,submit|null",
            "kyc.form|read|null",
            "kyc.task|read,authorize|null",
            "lender.advance|read,authorize|LENDER",
            "lender.assessment|read,execute|LENDER",
            "lender.collection|read,execute|LENDER",
            "lender.portfolio|read,execute|LENDER",
            "merchant.account|read|MERCHANT",
            "merchant.advance|read,create,delete,submit,authorize,simulate|MERCHANT",
            "merchant.inventory|read,create,modify,execute|MERCHANT",
            "merchant.invoice|read,execute|MERCHANT",
            "merchant.khata|read,create,execute|MERCHANT",
            "merchant.order|read,create|MERCHANT",
            "merchant.payment|read,execute,simulate|MERCHANT",
            "merchant.statement|read,execute,export|MERCHANT",
            "merchant.tab|read,create,execute|MERCHANT",
            "merchant.withdrawal|read,execute|MERCHANT",
            "ops.audit|read|null",
            "ops.bank_account_approval|read,authorize|null",
            "ops.complaint|read,create,modify|null",
            "ops.dispute|read,create,modify|null",
            "ops.incident|read,create,modify|null",
            "ops.kyc_check|read,create|null",
            "ops.merchant_approval|read,authorize|null",
            "ops.monitoring|execute|null",
            "ops.onboarding_request|read,authorize|null",
            "ops.recon|read,modify,execute|null",
            "ops.report|read,execute|null",
            "ops.supplier_approval|read,authorize|null",
            "ops.suspension|read,administer|null",
            "org.onboarding|read,create,authorize|null",
            "org.rights|read,administer|null",
            "org.suspension|administer|null",
            "org.tree|read|null",
            "org.user|read,create,delete,administer|null",
            "rbac.role|read,create,modify,delete|null",
            "rbac.assignment|read,create,authorize,administer|null",
            "rbac.grant|read,create,administer|null",
            "rbac.review|read,create,administer|null",
            "rbac.audit|read|null",
            "rbac.user|read|null",
            "supplier.account|read|SUPPLIER",
            "supplier.bank_account|read,modify,delete|SUPPLIER",
            "supplier.catalogue|read,create,delete|SUPPLIER",
            "supplier.invoice|read,create|SUPPLIER",
            "supplier.order|read|SUPPLIER",
            "supplier.statement|read,execute,export|SUPPLIER",
    };

    /** name|kind|approval|maxHours(0)|inherits(space)|grid: row=verbs */
    private static final String[] ROLES = {
            "kyc_applicant|ORG|none|0||kyc.application=read,modify,submit,delete;kyc.form=read",
            "org_viewer|ORG|none|0||kyc.application=read;kyc.form=read;org.tree=read;org.user=read;org.onboarding=read;"
                    + "org.rights=read;merchant.account=read;merchant.advance=read;merchant.inventory=read;merchant.invoice=read;"
                    + "merchant.khata=read;merchant.order=read;merchant.payment=read;merchant.statement=read;merchant.tab=read;"
                    + "merchant.withdrawal=read;supplier.account=read;supplier.bank_account=read;supplier.catalogue=read;"
                    + "supplier.invoice=read;supplier.order=read;supplier.statement=read;lender.advance=read;"
                    + "lender.assessment=read;lender.collection=read;lender.portfolio=read",
            "org_staff|ORG|none|0|org_viewer|merchant.inventory=create,modify,execute;merchant.khata=create,execute;"
                    + "merchant.order=create;merchant.tab=create,execute;merchant.statement=execute,export;"
                    + "merchant.advance=create,delete,submit;supplier.catalogue=create,delete;supplier.invoice=create;"
                    + "supplier.bank_account=modify,delete;supplier.statement=execute,export;lender.assessment=execute;"
                    + "lender.portfolio=execute",
            "org_admin|ORG|org_admin|0|org_staff org_user_admin org_onboarder org_access_admin|"
                    + "merchant.withdrawal=execute;merchant.payment=execute;merchant.invoice=execute;merchant.advance=authorize;"
                    + "lender.advance=authorize;lender.collection=execute;kyc.application=modify,submit,delete;"
                    + "org.user=create,delete,administer;org.onboarding=create,authorize;org.rights=administer;"
                    + "org.suspension=administer;rbac.assignment=read,create,authorize,administer;"
                    + "rbac.grant=read,create,administer;rbac.review=read,administer;rbac.role=read,create,modify,delete",
            "org_user_admin|ORG|org_admin|0|org_staff|org.user=create,delete,administer;rbac.assignment=read,create,administer",
            "org_onboarder|ORG|org_admin|0|org_staff|org.onboarding=create,authorize;org.rights=administer;rbac.grant=read,create",
            "org_access_admin|ORG|org_admin|0|org_staff|rbac.role=read,create,modify,delete",
            "platform_auditor|PLATFORM|platform|0||rbac.audit=read;@allrows=read",
            "platform_operations|PLATFORM|platform|0|platform_auditor|ops.incident=create,modify;ops.complaint=create,modify;"
                    + "ops.dispute=create,modify;ops.recon=modify,execute;ops.report=execute;ops.kyc_check=create;"
                    + "ops.monitoring=execute;ops.suspension=administer",
            "platform_ops_reviewer|PLATFORM|platform|0|platform_auditor|ops.merchant_approval=authorize;"
                    + "ops.supplier_approval=authorize;ops.bank_account_approval=authorize;ops.onboarding_request=authorize;"
                    + "kyc.task=authorize",
            "platform_compliance_officer|PLATFORM|compliance|0|platform_auditor|kyc.task=authorize;admin.sar=create,submit;"
                    + "admin.dsar=create,export",
            "platform_mlro|PLATFORM|compliance|0|platform_auditor|kyc.task=authorize;admin.sar=create,submit;"
                    + "rbac.assignment=read,authorize",
            "platform_senior_approver|PLATFORM|compliance|0|platform_auditor|kyc.task=authorize;"
                    + "rbac.assignment=read,authorize",
            "platform_user_admin|PLATFORM|platform|0||org.user=read,create,delete,administer;org.tree=read;"
                    + "admin.access=read,administer;admin.api_client=read,create,administer;rbac.role=read,create,modify,delete;"
                    + "rbac.user=read;rbac.assignment=read,create,authorize,administer;rbac.grant=read,create,administer;"
                    + "rbac.review=read,create,administer;rbac.audit=read",
            "platform_engineer|PLATFORM|platform|0||admin.platform_ops=read,execute,administer;"
                    + "admin.connector=read,modify,execute,administer;admin.config=read,modify;admin.simulation=read;"
                    + "ops.monitoring=execute",
            "platform_break_glass|PLATFORM|break_glass|8||admin.access=administer;org.user=create,delete,administer;"
                    + "ops.suspension=administer;org.suspension=administer;rbac.assignment=create,administer;@allrows=read",
            "lender_officer|PLATFORM|platform|0||lender.advance=read,authorize;lender.assessment=read,execute;"
                    + "lender.collection=read,execute;lender.portfolio=read,execute",
            "sandbox_tester|SANDBOX|none|0||admin.simulation=read,simulate;merchant.payment=simulate;"
                    + "merchant.advance=simulate",
            "legacy_tpts_ops|PLATFORM|platform|0||kyc.application=modify,submit,delete;org.onboarding=create,authorize;"
                    + "org.user=create,delete;rbac.role=create,modify,delete;rbac.assignment=create,authorize,administer",
            "legacy_tpts_ops_admin|PLATFORM|platform|0||org.rights=administer;org.suspension=administer;org.user=administer",
            "legacy_mindlabs_admin|PLATFORM|platform|0||admin.aml_alert=read;admin.dsar=read,create,export;"
                    + "admin.sar=read,create,submit;admin.bootstrap=administer;kyc.application=read,modify,submit,delete;"
                    + "kyc.form=read;kyc.task=read;org.onboarding=read,create;org.rights=read,administer;"
                    + "org.suspension=administer",
            "legacy_mindlabs_review|PLATFORM|platform|0||kyc.task=authorize;org.onboarding=authorize",
    };

    private static final String[] SOD = {
            "ssd-access-admin-not-reviewer|STATIC|{platform_user_admin}|{platform_ops_reviewer,platform_compliance_officer,platform_mlro,platform_senior_approver}|null|null",
            "ssd-two-review-stages|STATIC|{platform_ops_reviewer}|{platform_senior_approver}|null|null",
            "ssd-mlro-independent|STATIC|{platform_mlro}|{platform_user_admin}|null|null",
            "ssd-break-glass-separate|STATIC|{platform_break_glass}|{platform_auditor,platform_operations,platform_ops_reviewer,platform_compliance_officer,platform_mlro,platform_senior_approver,platform_user_admin,platform_engineer,lender_officer}|null|null",
            "dsd-one-review-stage-per-session|DYNAMIC|{platform_ops_reviewer}|{platform_compliance_officer}|null|null",
            "obj-maker-not-checker|OBJECT|{}|{}|kyc.application:submit|kyc.task:authorize",
            "obj-no-self-approval-of-money|OBJECT|{}|{}|merchant.withdrawal:execute|ops.bank_account_approval:authorize",
    };

    static void load(JdbcTemplate db, Policy policy) {
        for (String spec : ROWS) {
            String[] p = spec.split("\\|");
            db.update("""
                    INSERT INTO rbac_resources (id, service, description, declared_verbs, used_verbs, tenant_types)
                    VALUES (?,?,?,?,?,?)
                    ON CONFLICT (id) DO UPDATE SET service=EXCLUDED.service, declared_verbs=EXCLUDED.declared_verbs,
                        used_verbs=EXCLUDED.used_verbs, tenant_types=EXCLUDED.tenant_types""",
                    p[0], S, p[0].replace('.', ' '), Verbs.mask(List.of(p[1].split(","))),
                    Verbs.mask(List.of(p[1].split(","))), "null".equals(p[2]) ? null : p[2]);
        }
        // the entitlement cell keeps its attribute like the seed used to set it
        db.update("UPDATE rbac_resources SET grant_verbs = grant_verbs | ?, grant_attribute='child_type' WHERE id='org.onboarding'",
                Verbs.bit("create"));

        for (String spec : ROLES) {
            String[] p = spec.split("\\|", 6);
            boolean seeded = p[0].equals("org_admin") || p[0].equals("org_viewer");
            int allRows = p[5].contains("@allrows=") ? Verbs.bit("read") : 0;
            db.update("""
                    INSERT INTO roles (name, description, kind, approval, max_hours, all_rows_verbs, seeded)
                    VALUES (?,?,?,?,?,?,'%s')
                    ON CONFLICT (name) DO UPDATE SET description=EXCLUDED.description, kind=EXCLUDED.kind,
                        approval=EXCLUDED.approval, max_hours=EXCLUDED.max_hours, all_rows_verbs=EXCLUDED.all_rows_verbs"""
                    .formatted(seeded ? "TRUE" : "FALSE"),
                    p[0], p[0], p[1], p[2], "0".equals(p[3]) ? null : Integer.parseInt(p[3]), allRows);
        }
        // second pass for grids and inheritance (FK: a role references only already-inserted roles)
        for (String spec : ROLES) {
            String[] p = spec.split("\\|", 6);
            db.update("DELETE FROM rbac_role_grid WHERE role=?", p[0]);
            for (String g : p[5].split(";")) {
                if (g.isBlank() || g.startsWith("@")) continue;
                int eq = g.indexOf('=');
                db.update("INSERT INTO rbac_role_grid (role, resource, verbs) VALUES (?,?,?)",
                        p[0], g.substring(0, eq), Verbs.mask(Arrays.asList(g.substring(eq + 1).split(","))));
            }
            db.update("DELETE FROM role_inheritance WHERE senior=?", p[0]);
            for (String j : p[4].isBlank() ? new String[0] : p[4].split(" ")) {
                db.update("INSERT INTO role_inheritance (senior, junior) VALUES (?,?)", p[0], j);
            }
        }

        db.update("DELETE FROM sod_rules");
        for (String spec : SOD) {
            String[] p = spec.split("\\|", 6);
            db.update("INSERT INTO sod_rules (id, kind, roles_a, roles_b, cell_a, cell_b, reason) VALUES (?,?,?::text[],?::text[],?,?,?)",
                    p[0], p[1], p[2], p[3],
                    "null".equals(p[4]) ? null : p[4], "null".equals(p[5]) ? null : p[5], "test fixture: " + p[0].replace('-', ' '));
        }
        db.update("DELETE FROM same_row_exemptions");
        db.update("INSERT INTO same_row_exemptions (resource, reason) VALUES ('merchant.advance', 'test fixture: maker/checker is the lender side')");
        db.update("DELETE FROM ceiling_baseline");
        db.update("INSERT INTO ceiling_baseline (role) SELECT 'kyc_applicant' WHERE true");
        db.update("INSERT INTO ceiling_baseline (role) SELECT 'org_admin' WHERE true");
        policy.reload();
    }

}
