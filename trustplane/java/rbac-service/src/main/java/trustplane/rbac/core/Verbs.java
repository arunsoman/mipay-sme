package trustplane.rbac.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The ten fixed columns of the permission grid (fapi2-design.md Appendix G). A permission is
 * one cell {@code <row>:<verb>}; a role stores one bitmask per row.
 */
public final class Verbs {
    private Verbs() {}

    public static final List<String> ALL = List.of(
            "read", "create", "modify", "delete", "submit", "authorize", "execute", "export", "administer", "simulate");

    public static final int READ = 1, SUBMIT = 16, AUTHORIZE = 32, ADMINISTER = 256, SIMULATE = 512;

    /** Bit of a verb name, or 0 when it is not a grid verb. */
    public static int bit(String verb) {
        int i = ALL.indexOf(verb == null ? "" : verb.toLowerCase());
        return i < 0 ? 0 : 1 << i;
    }

    public static int mask(List<String> verbs) {
        int m = 0;
        if (verbs == null) return 0;
        for (String v : verbs) {
            int b = bit(v);
            if (b == 0) throw new IllegalArgumentException("unknown verb " + v);
            m |= b;
        }
        return m;
    }

    public static List<String> names(int mask) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < ALL.size(); i++) if ((mask & (1 << i)) != 0) out.add(ALL.get(i));
        return out;
    }

    /** A parsed cell. Accepts {@code row:verb} and the design's {@code row.verb} spelling. */
    public record Cell(String row, String verb, int bit) {
        public String id() { return row + ":" + verb; }
    }

    public static Cell parse(String op) {
        if (op == null) return null;
        int i = op.lastIndexOf(':');
        if (i < 0) i = op.lastIndexOf('.');
        if (i <= 0 || i == op.length() - 1) return null;
        String row = op.substring(0, i), verb = op.substring(i + 1).toLowerCase();
        int b = bit(verb);
        if (b == 0 || !row.matches("^[a-z]+\\.[a-z_]+$")) return null;
        return new Cell(row, verb, b);
    }
}
