package trustplane.rbac;

import org.junit.jupiter.api.Test;
import trustplane.rbac.core.RootAdminPolicy;
import trustplane.rbac.core.Verbs;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RootAdminPolicyTest {
    @Test
    void bootstrapAdminOnlyManagesUsersRolesAndAssignments() {
        var grid = RootAdminPolicy.grid();
        assertEquals(Set.of("org.user", "rbac.role", "rbac.user", "rbac.assignment"), grid.keySet());
        assertEquals(Set.of("read", "create", "administer"), Set.copyOf(Verbs.names(grid.get("org.user"))));
        assertEquals(Set.of("read", "create", "modify", "delete"), Set.copyOf(Verbs.names(grid.get("rbac.role"))));
        assertFalse(grid.containsKey("org.onboarding"));
        assertFalse(grid.containsKey("admin.access"));
    }
}
