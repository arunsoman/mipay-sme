package com.mipay.sme.security.auth;

import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifecycle service on a real Postgres: creation in an eligible org commits the user and
 * its ENROL command in one transaction; ineligible orgs are refused; login/contact changes
 * enqueue UPDATE_LOGIN only once the user has an enrolled subject. No mocks anywhere.
 */
@ExtendWith(AuthOutboxProcessorRealIdpIT.RealInfra.class)
@SpringBootTest(properties = {
        "spring.profiles.active=postgres",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:25433/auth_it_lifecycle",
        "spring.datasource.username=t",
        "spring.datasource.password=t",
        "app.identity-mode=gateway",
        "app.auth-provider=trustplane",
        "app.auth-url=https://127.0.0.1:18443",
        "app.outbox.poll-millis=3600000",
        "app.gateway-context-secret-file=build/test-pki/_vol/ctx-secret"
})
class UserLifecycleServicePostgresIT {

    @Autowired UserLifecycleService lifecycle;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void provision() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:25433/postgres", "t", "t");
             var s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS auth_it_lifecycle WITH (FORCE)");
            s.execute("CREATE DATABASE auth_it_lifecycle");
        }
    }

    private String platformOrg() {
        return jdbc.queryForObject("SELECT id FROM organizations WHERE org_type='PLATFORM' LIMIT 1", String.class);
    }

    private UserLifecycleService.Registration reg(String orgId, String phone, Enums.OrgMemberRole orgRole) {
        return new UserLifecycleService.Registration(orgId, "Lifecycle IT", null, phone,
                "it-" + phone + "@uat.na", Enums.UserRole.TPTS_OPS, orgRole, "lifecycle-it");
    }

    @Test
    void createCommitsUserAndEnrolCommandAtomically() {
        User user = lifecycle.create(reg(platformOrg(), "+264811000001", Enums.OrgMemberRole.ADMIN));
        assertEquals(Enums.UserStatus.PENDING, user.getStatus());
        assertEquals(platformOrg(), user.getOrgId());
        assertTrue(user.getUsername() != null && !user.getUsername().isBlank(), "username generated");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM auth_outbox WHERE user_id=? AND command='ENROL' AND dedupe_key=?",
                Integer.class, user.getId(), "enrol:" + user.getId()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE phone=? AND status NOT IN ('PENDING','ACTIVE')",
                Integer.class, user.getPhone()));
    }

    @Test
    void onePhoneMayServeSeveralLogins() {
        // V20: one person may administer several organizations; each login keeps its own username
        User a = lifecycle.create(reg(platformOrg(), "+264811000002", Enums.OrgMemberRole.ADMIN));
        User b = lifecycle.create(reg(platformOrg(), "+264811000002", Enums.OrgMemberRole.ADMIN));
        assertNotEquals(a.getUsername(), b.getUsername());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM users WHERE phone='+264811000002'", Integer.class));
    }

    @Test
    void pendingApplicantOrgAllowsAdminCreationOnly() {
        String orgId = jdbc.queryForObject(
                "INSERT INTO organizations (id, org_type, name, status, licence_type, created_by) "
                        + "VALUES (?, 'MERCHANT', 'Pending Applicant IT', 'PENDING', 'PSP', 'it') RETURNING id",
                String.class, "org-it-" + System.nanoTime() % 100000000);
        User admin = lifecycle.create(reg(orgId, "+264811000003", Enums.OrgMemberRole.ADMIN));
        assertEquals(Enums.UserStatus.PENDING, admin.getStatus());
        assertThrows(ForbiddenException.class, () -> lifecycle.create(
                reg(orgId, "+264811000004", Enums.OrgMemberRole.STAFF)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM users WHERE org_id=?",
                Integer.class, orgId));
    }

    @Test
    void blockedOrgRefusesCreation() {
        String orgId = jdbc.queryForObject(
                "INSERT INTO organizations (id, org_type, name, status, licence_type, created_by) "
                        + "VALUES (?, 'MERCHANT', 'Blocked IT', 'BLOCKED', 'PSP', 'it') RETURNING id",
                String.class, "org-it-" + System.nanoTime() % 100000000);
        assertThrows(ForbiddenException.class, () -> lifecycle.create(
                reg(orgId, "+264811000005", Enums.OrgMemberRole.ADMIN)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM users WHERE org_id=?",
                Integer.class, orgId));
    }

    @Test
    void loginContactChangeEnqueuesUpdateLoginOnlyWhenEnrolled() {
        User user = lifecycle.create(reg(platformOrg(), "+264811000006", Enums.OrgMemberRole.ADMIN));
        // no enrolled subject yet -> no provider command
        lifecycle.updateLoginContact(user.getId(), null, "+264811000006x", null);
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM auth_outbox WHERE user_id=? AND command='UPDATE_LOGIN'",
                Integer.class, user.getId()));
        // enroll the link (subject NOT NULL), then the change reaches the provider
        jdbc.update("INSERT INTO identity_links (id, user_id, provider, issuer, subject, status, idp_enabled) "
                + "VALUES (?, ?, 'trustplane', 'https://idp.fapi.local:8443', 'acc_it_x', 'PENDING', false)",
                "lnk" + user.getId().substring(0, 28), user.getId());
        lifecycle.updateLoginContact(user.getId(), "lifecycle.it.2", "+264811000007", null);
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM auth_outbox WHERE user_id=? AND command='UPDATE_LOGIN'",
                Integer.class, user.getId()));
        // the dedup key is state-fresh (monotonic), so flipping back to an older contact value
        // must still enqueue — a DONE row with a historical key never blocks a real change
        lifecycle.updateLoginContact(user.getId(), "lifecycle.it.2", "+264811000006x", null);
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM auth_outbox WHERE user_id=? AND command='UPDATE_LOGIN'",
                Integer.class, user.getId()));
        Map<String, Object> payload = jdbc.queryForMap(
                "SELECT payload::text AS p FROM auth_outbox WHERE user_id=? AND command='UPDATE_LOGIN' "
                        + "ORDER BY available_at DESC LIMIT 1", user.getId());
        assertTrue(payload.get("p").toString().contains("lifecycle.it.2"));
    }

    @AfterAll
    static void dropDb() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:25433/postgres", "t", "t");
             var s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS auth_it_lifecycle WITH (FORCE)");
        } catch (Exception ignored) { }
    }
}
