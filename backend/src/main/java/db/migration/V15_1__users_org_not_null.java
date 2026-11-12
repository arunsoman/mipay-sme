package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Org binding made permanent (cut-over P3.10a; the plan calls this migration "V15b",
 * Flyway needs a numeric version so it lands as 15.1).
 *
 * <p>Binds the users that the deterministic rules can place — the same rules as
 * {@code OrgProvisioningService.ensureEntityOrg} and the {@code OrgBindingBackfill}
 * admin tool:</p>
 * <ol>
 *   <li>platform roles (TPTS_OPS, MINDLABS_ADMIN) → the PLATFORM org;</li>
 *   <li>merchant / supplier / lender entity owners → their entity's org (get-or-create,
 *       status ACTIVE, parented at the platform root, closure rows kept).</li>
 * </ol>
 *
 * <p>Every user that still has no home org afterwards is <b>listed</b> and the migration
 * <b>fails</b> — a manual decision is required (D4: bind or archive, never guess). The
 * transaction rolls back, so nothing is half-applied; fix the listed users and boot again.</p>
 *
 * <p>Once the list is empty, {@code users.org_id} becomes NOT NULL with an FK to
 * organizations — per-statement NOT NULL is safe because the lifecycle writes the home org
 * before anything flushes (bindToOrg sets it first; createPreBinding stays unflushed until
 * bindToOrg). The pre-deploy tool is {@code GET/POST /api/admin/migration/org-binding}.</p>
 */
public class V15_1__users_org_not_null extends BaseJavaMigration {

    private static final Logger LOG = Logger.getLogger(V15_1__users_org_not_null.class.getName());

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        // Flyway may run this migration with autoCommit off and commit for us; only manage
        // the transaction ourselves when the connection came in autoCommit-on
        boolean selfManaged = c.getAutoCommit();
        if (selfManaged) c.setAutoCommit(false);
        try {
            String platformOrgId = platformOrg(c);
            bindPlatformUsers(c, platformOrgId);
            bindEntityOwners(c, platformOrgId, "merchants", "MERCHANT", "business_name");
            bindEntityOwners(c, platformOrgId, "suppliers", "SUPPLIER", "business_name");
            bindEntityOwners(c, platformOrgId, "lenders", "LENDER", "name");
            List<String> leftover = unresolved(c);
            if (!leftover.isEmpty()) {
                throw new IllegalStateException("D4: users with no resolvable home org — decide manually "
                        + "(bind via /api/admin/migration/org-binding or archive), then boot again:\n"
                        + String.join("\n", leftover));
            }
            enforce(c);
            if (selfManaged) c.commit();
        } catch (Exception e) {
            if (selfManaged) c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(selfManaged);
        }
    }

    private String platformOrg(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id FROM organizations WHERE org_type='PLATFORM' AND entity_id IS NULL LIMIT 1")) {
            if (!rs.next()) throw new IllegalStateException(
                    "no PLATFORM org exists; users with platform roles cannot be bound");
            return rs.getString(1);
        }
    }

    private void bindPlatformUsers(Connection c, String platformOrgId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET org_id=? WHERE org_id IS NULL AND role IN ('TPTS_OPS','MINDLABS_ADMIN')")) {
            ps.setString(1, platformOrgId);
            int n = ps.executeUpdate();
            if (n > 0) LOG.info("org-binding: " + n + " platform user(s) bound to " + platformOrgId);
        }
    }

    /** Binds owners of entity rows to the entity's org, creating it when missing (as ensureEntityOrg does). */
    private void bindEntityOwners(Connection c, String platformOrgId, String table, String orgType,
                                  String nameColumn) throws SQLException {
        String select = "SELECT e.id, e.user_id, e." + nameColumn + " FROM " + table + " e "
                + "JOIN users u ON u.id = e.user_id WHERE u.org_id IS NULL";
        List<String[]> entities = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(select)) {
            while (rs.next()) entities.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
        }
        try (PreparedStatement findOrg = c.prepareStatement(
                     "SELECT id FROM organizations WHERE org_type=? AND entity_id=? LIMIT 1");
             PreparedStatement newOrg = c.prepareStatement(
                     "INSERT INTO organizations (id, org_type, name, entity_id, parent_id, status, created_by, created_at, version) "
                             + "VALUES (?,?,?,?,?,'ACTIVE','system:binding',now(),0)");
             PreparedStatement bind = c.prepareStatement(
                     "UPDATE users SET org_id=?, org_role=CASE WHEN org_role IS NULL THEN 'ADMIN' ELSE org_role END "
                             + "WHERE id=? AND org_id IS NULL");
             PreparedStatement closure = c.prepareStatement(
                     "INSERT INTO org_closures (ancestor_id, descendant_id, depth) VALUES (?,?,?)")) {
            for (String[] e : entities) {
                String entityId = e[0], userId = e[1], name = e[2];
                String orgId;
                try (ResultSet rs = findOrgWith(c, findOrg, orgType, entityId)) {
                    if (rs.next()) {
                        orgId = rs.getString(1);
                    } else {
                        orgId = UUID.randomUUID().toString().toLowerCase();
                        newOrg.setString(1, orgId);
                        newOrg.setString(2, orgType);
                        newOrg.setString(3, name == null || name.isBlank() ? orgType : name);
                        newOrg.setString(4, entityId);
                        newOrg.setString(5, platformOrgId);
                        newOrg.executeUpdate();
                        closure.setString(1, orgId);
                        closure.setString(2, orgId);
                        closure.setInt(3, 0);
                        closure.executeUpdate();
                        closure.setString(1, platformOrgId);
                        closure.setString(2, orgId);
                        closure.setInt(3, 1);
                        closure.executeUpdate();
                    }
                }
                bind.setString(1, orgId);
                bind.setString(2, userId);
                bind.executeUpdate();
            }
        }
        if (!entities.isEmpty()) LOG.info("org-binding: " + entities.size() + " " + orgType + " owner(s) bound");
    }

    private ResultSet findOrgWith(Connection c, PreparedStatement ps, String orgType, String entityId)
            throws SQLException {
        ps.setString(1, orgType);
        ps.setString(2, entityId);
        return ps.executeQuery();
    }

    private List<String> unresolved(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, phone, role FROM users WHERE org_id IS NULL ORDER BY phone")) {
            while (rs.next()) out.add("  " + rs.getString(1) + " " + rs.getString(2) + " role=" + rs.getString(3));
        }
        return out;
    }

    private void enforce(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("ALTER TABLE users ALTER COLUMN org_id SET NOT NULL");
            try (ResultSet rs = s.executeQuery(
                    "SELECT 1 FROM pg_constraint WHERE conname='fk_users_org' AND conrelid='users'::regclass")) {
                if (!rs.next()) s.execute(
                        "ALTER TABLE users ADD CONSTRAINT fk_users_org FOREIGN KEY (org_id) REFERENCES organizations(id)");
            }
        }
    }
}
