package trustplane.rbac.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import trustplane.rbac.Env;

import java.util.Set;

/**
 * Typed, immutable configuration for the RBAC service (env-driven, Go parity).
 *
 * Registered as an explicit @Bean (a record annotated @Component makes Spring
 * autowire its canonical constructor, which fails on plain String components).
 */
public record RbacConfig(
        String dsn,
        String grpcListen,        // decision plane (mTLS gRPC)
        String adminListen,       // admin REST over TLS+mTLS
        String tlsCert, String tlsKey, String internalCA,
        String ctxSecretPath,
        String seedFile,
        long ctxSkewSeconds,
        String environment,       // dev | sandbox | uat | production (RBAC_ENVIRONMENT)
        String fixturesFile,      // e2e demo orgs + assignments; dev/sandbox only
        String coreCN) {          // mesh CN of the MiPay core (internal assignment/grant API)

    public static RbacConfig fromEnv() {
        RbacConfig c = new RbacConfig(
                Env.get("RBAC_DSN", ""),
                Env.get("RBAC_GRPC_LISTEN", ":5051"),
                Env.get("RBAC_ADMIN_LISTEN", ":8443"),
                Env.get("RBAC_TLS_CERT", "/pki/rbac-server.pem"),
                Env.get("RBAC_TLS_KEY", "/pki/rbac-server-key.pem"),
                Env.get("RBAC_INTERNAL_CA", "/pki/platform-ca.pem"),
                Env.get("RBAC_CTX_SECRET", "/pki/ctx-secret"),
                Env.get("RBAC_SEED_FILE", "/config/rbac/grid.yaml"),
                Env.getInt("RBAC_CTX_SKEW_SECONDS", 120),
                Env.get("RBAC_ENVIRONMENT", "dev"),
                Env.get("RBAC_FIXTURES_FILE", ""),
                Env.get("RBAC_CORE_CN", "sme-mipay-mesh"));
        if (!Set.of("dev", "sandbox", "uat", "production").contains(c.environment())) {
            throw new IllegalStateException("RBAC_ENVIRONMENT must be dev|sandbox|uat|production, got " + c.environment());
        }
        return c;
    }

    public boolean isDev() { return environment.equals("dev") || environment.equals("sandbox"); }

    public boolean isProduction() { return environment.equals("production"); }

    @Configuration
    static class Wire {
        @Bean RbacConfig rbacConfig() { return RbacConfig.fromEnv(); }
    }
}
