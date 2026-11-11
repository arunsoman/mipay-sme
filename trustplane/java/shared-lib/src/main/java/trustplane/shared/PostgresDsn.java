package trustplane.shared;

/** Parses Go-style postgres DSNs into JDBC coordinates for Hikari. */
public record PostgresDsn(String host, int port, String db, String user, String password, String params) {

    /** "postgres://user:pass@host:port/db?sslmode=disable" (port default 5432). */
    public static PostgresDsn parse(String dsn) {
        String s = dsn.trim();
        if (!s.startsWith("postgres://") && !s.startsWith("postgresql://")) {
            throw new IllegalArgumentException("unsupported DSN scheme (postgres:// required)");
        }
        s = s.substring(s.indexOf("://") + 3);
        String rest = s;
        String user = "", password = "";
        int at = rest.indexOf('@');
        if (at >= 0) {
            String creds = rest.substring(0, at);
            rest = rest.substring(at + 1);
            int colon = creds.indexOf(':');
            if (colon >= 0) {
                user = creds.substring(0, colon);
                password = creds.substring(colon + 1);
            } else {
                user = creds;
            }
        }
        String params = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            params = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        String host = rest;
        int port = 5432;
        String db = "";
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            db = rest.substring(slash + 1);
            String hostPort = rest.substring(0, slash);
            int c = hostPort.indexOf(':');
            if (c >= 0) {
                host = hostPort.substring(0, c);
                port = Integer.parseInt(hostPort.substring(c + 1));
            } else {
                host = hostPort;
            }
        }
        if (params.isEmpty()) {
            params = "sslmode=disable";
        }
        return new PostgresDsn(host, port, db, user, password, params);
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + db;
    }
}
