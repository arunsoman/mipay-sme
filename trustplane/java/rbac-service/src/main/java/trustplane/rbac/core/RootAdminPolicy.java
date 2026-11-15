package trustplane.rbac.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed bootstrap administrator scope: manage users, roles and assignments only. */
public final class RootAdminPolicy {
    public static final String ROLE = "organization_root";
    public static final String SYSTEM_ACTOR = "system:bootstrap-role-scope";

    private RootAdminPolicy() { }

    public static Map<String, Integer> grid() {
        Map<String, Integer> grid = new LinkedHashMap<>();
        grid.put("org.user", Verbs.READ | Verbs.bit("create") | Verbs.ADMINISTER);
        grid.put("rbac.role", Verbs.READ | Verbs.bit("create") | Verbs.bit("modify") | Verbs.bit("delete"));
        grid.put("rbac.user", Verbs.READ);
        // authorize: the root admin approves four-eyes role requests (afriqpay hotfix, recorded 2026-11-16)
        grid.put("rbac.assignment", Verbs.READ | Verbs.bit("create") | Verbs.ADMINISTER | Verbs.AUTHORIZE);
        return grid;
    }
}
