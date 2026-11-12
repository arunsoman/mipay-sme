package trustplane.rbac;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import trustplane.rbac.core.Policy;
import trustplane.rbac.core.RolePack;
import trustplane.shared.PostgresDsn;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the compliance role pack from an RBAC database that the services have filled
 * (seed + every service's own catalogue registration), without going through the gateway:
 * {@code ROLE_PACK_DSN=postgres://... ROLE_PACK_OUT=docs/rbac/role-pack-v1.md
 * ./gradlew :rbac-service:test --tests '*RolePackExport*'}. Skipped unless both are set.
 * The live path stays {@code make role-pack} (GET /rbac/admin/export/role-pack).
 */
class RolePackExport {

    @Test
    void export() throws Exception {
        String raw = System.getenv("ROLE_PACK_DSN"), out = System.getenv("ROLE_PACK_OUT");
        Assumptions.assumeTrue(raw != null && !raw.isBlank() && out != null && !out.isBlank(), "ROLE_PACK_DSN / ROLE_PACK_OUT not set");
        PostgresDsn dsn = PostgresDsn.parse(raw);
        JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(dsn.jdbcUrl(), dsn.user(), dsn.password()));
        Policy policy = new Policy(db);
        policy.reload();
        String env = System.getenv().getOrDefault("ROLE_PACK_ENV", "dev");
        Files.writeString(Path.of(out), new RolePack(db, policy).render(env), StandardCharsets.UTF_8);
    }
}
