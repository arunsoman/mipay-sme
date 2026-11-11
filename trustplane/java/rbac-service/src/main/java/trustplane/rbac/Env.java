package trustplane.rbac;

/** Environment access mirroring the Go services' env conventions. */
public final class Env {
    private Env() {}

    public static String get(String key, String def) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return def;
        return v;
    }

    public static int getInt(String key, int def) {
        try { return Integer.parseInt(get(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }
}
