package trustplane.shared;

import java.util.List;

/**
 * Standard permission matrix applied to every controller resource. The user
 * requirement is explicit: each controller gets create, update, delete and
 * approve permissions (read is included so GET endpoints authorize too).
 * Services may add resource-specific extras via {@link CrudResource#extra()}.
 */
public final class PermissionMatrix {
    public static final List<String> STANDARD = List.of("create", "read", "update", "delete", "approve");

    private PermissionMatrix() {}

    public static String id(String resource, String action) {
        return resource + ":" + action;
    }
}
