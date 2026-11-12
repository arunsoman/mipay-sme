package trustplane.rbac.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The compliance sign-off pack (idp-rbac-cutover-plan.md P2.5a): one page per role with its
 * ticked cells, the endpoints each cell covers, the SoD sets it is part of and who approves
 * granting it. Compliance signs it before {@code app.rbac.mode=enforce} (P6).
 */
@Service
@lombok.RequiredArgsConstructor
public class RolePack {

    private final JdbcTemplate db;
    private final Policy policy;

    static final Map<String, String> APPROVAL = Map.of(
            "none", "No second approval (granted by an org_admin of the org, within its ceiling; access reviews catch drift)",
            "org_admin", "A second org_admin of the same org; if none, an org_admin of the parent org; if none, a platform_user_admin",
            "platform", "A second platform_user_admin",
            "compliance", "The platform_mlro (when granting platform_mlro itself: a platform_senior_approver)",
            "break_glass", "Requested by the person; approved by a platform_user_admin at AAL2; at most 8 h; the security contact is alerted");

    public String render(String environment) {
        Policy.Snapshot s = policy.get();
        Map<String, Map<Integer, List<String>>> endpoints = new HashMap<>();
        db.query("SELECT resource, verb, method, path FROM rbac_cell_endpoints ORDER BY path, method", rs -> {
            endpoints.computeIfAbsent(rs.getString("resource"), k -> new TreeMap<>())
                    .computeIfAbsent(rs.getInt("verb"), k -> new ArrayList<>())
                    .add(rs.getString("method") + " " + rs.getString("path"));
        });
        Map<String, Integer> holders = new HashMap<>();
        db.query("SELECT role, count(DISTINCT subject) AS n FROM assignments WHERE status='ACTIVE' GROUP BY role",
                rs -> { holders.put(rs.getString("role"), rs.getInt("n")); });

        StringBuilder b = new StringBuilder();
        b.append("# RBAC role pack for compliance sign-off\n\n");
        b.append("Generated ").append(LocalDate.now()).append(" from the live RBAC service (environment `").append(environment)
                .append("`). Source of truth: `trustplane/config/rbac/grid.yaml`; the endpoints come from the code's own registration.\n\n");
        b.append("Version 1 of the grid reproduces today's access one-to-one (decision D9), so shadow mode can prove the two agree. ")
                .append("Tightening happens afterwards through the quarterly access review.\n\n");
        b.append("**Verbs:** read · create · modify · delete · submit · authorize · execute · export · administer · simulate. ")
                .append("A cell `row:verb` exists only if an endpoint uses it.\n\n");

        b.append("## Summary\n\n| Role | Kind | Inherits | Cells (with inherited) | Approval to grant | Holders |\n|---|---|---|---|---|---|\n");
        for (Policy.Role r : s.roles().values()) {
            b.append("| `").append(r.name()).append("` | ").append(r.kind()).append(" | ")
                    .append(r.juniors().isEmpty() ? "—" : "`" + String.join("`, `", r.juniors()) + "`").append(" | ")
                    .append(cells(s, r.name()).size()).append(" | ").append(r.approval()).append(" | ")
                    .append(holders.getOrDefault(r.name(), 0)).append(" |\n");
        }

        b.append("\n## Separation of duty\n\n| Rule | Kind | Applies to | Why |\n|---|---|---|---|\n");
        for (Policy.SodRule r : s.sod()) {
            String what = "OBJECT".equals(r.kind()) ? "`" + r.cellA() + "` × `" + r.cellB() + "`"
                    : "`" + String.join("`, `", r.a()) + "` × `" + String.join("`, `", r.b()) + "`";
            b.append("| ").append(r.id()).append(" | ").append(r.kind()).append(" | ").append(what).append(" | ").append(r.reason()).append(" |\n");
        }
        b.append("\nGrid rules checked on every save: no role ticks both `submit` and `authorize` on one row (exemptions below); ")
                .append("no platform role both administers and authorizes on customer rows (approving an access request, `rbac.assignment:authorize`, is access administration and is not counted); the `simulate` column is refused in production.\n\n");
        if (!s.exemptions().isEmpty()) {
            b.append("**Same-row exemptions:**\n\n");
            new TreeMap<>(s.exemptions()).forEach((row, why) -> b.append("- `").append(row).append("`: ").append(why).append("\n"));
            b.append("\n");
        }

        for (Policy.Role r : s.roles().values()) {
            b.append("---\n\n## `").append(r.name()).append("`\n\n").append(r.description()).append("\n\n");
            b.append("- **Kind:** ").append(r.kind()).append("ORG".equals(r.kind()) ? " (assigned on an org, scope own or subtree)" : "PLATFORM".equals(r.kind()) ? " (scope all)" : " (sandbox and UAT only)").append("\n");
            b.append("- **Inherits:** ").append(r.juniors().isEmpty() ? "nothing" : String.join(", ", r.juniors())).append("\n");
            b.append("- **Granting needs:** ").append(APPROVAL.getOrDefault(r.approval(), r.approval())).append("\n");
            if (r.maxHours() != null) b.append("- **Lasts at most:** ").append(r.maxHours()).append(" h\n");
            List<String> sod = new ArrayList<>();
            for (Policy.SodRule x : s.sod()) if (x.a().contains(r.name()) || x.b().contains(r.name())) sod.add(x.id() + " (" + x.kind() + ")");
            b.append("- **SoD sets:** ").append(sod.isEmpty() ? "none" : String.join(", ", sod)).append("\n\n");
            b.append("| Cell | From | Endpoints it covers |\n|---|---|---|\n");
            for (String[] c : cells(s, r.name())) {
                Verbs.Cell cell = Verbs.parse(c[0]);
                List<String> eps = endpoints.getOrDefault(cell.row(), Map.of()).getOrDefault(cell.bit(), List.of());
                Policy.Resource res = s.resources().get(cell.row());
                boolean orphan = res != null && (res.used() & cell.bit()) == 0;
                b.append("| `").append(c[0]).append("` | ").append(c[1]).append(" | ")
                        .append(orphan ? "⚠ no endpoint uses this cell" : eps.isEmpty() ? "(declared; endpoints not registered yet)" : "`" + String.join("`<br>`", eps) + "`")
                        .append(" |\n");
            }
            b.append("\nApproved for use: ______________________ (Compliance)    Date: __________\n\n");
        }
        return b.toString();
    }

    /** Effective cells of a role: [cell, "own" | "inherited from x" | "all rows"]. */
    List<String[]> cells(Policy.Snapshot s, String role) {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Policy.Role me = s.roles().get(role);
        for (String rn : s.expanded().getOrDefault(role, Set.of(role))) {
            Policy.Role r = s.roles().get(rn);
            if (r == null) continue;
            for (Policy.Resource res : s.resources().values()) {
                int mask = r.grid().getOrDefault(res.id(), 0) | (r.allRowsVerbs() & res.used());
                for (String v : Verbs.names(mask)) {
                    String id = res.id() + ":" + v;
                    if (!seen.add(id)) continue;
                    String from = rn.equals(role) ? ((r.grid().getOrDefault(res.id(), 0) & Verbs.bit(v)) != 0 ? "own" : "all rows")
                            : "inherited from `" + rn + "`";
                    out.add(new String[]{id, from});
                }
            }
        }
        out.sort((x, y) -> x[0].compareTo(y[0]));
        return me == null ? List.of() : out;
    }
}
