package trustplane.rbac.config;

import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleKey;
import org.springframework.boot.ssl.SslOptions;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.preauth.RequestHeaderAuthenticationFilter;
import trustplane.rbac.config.RbacConfig;
import trustplane.shared.GatewayContextFilter;
import trustplane.shared.PemKeyStore;

/**
 * Admin-plane security:
 *  - the TLS listener REQUIRES a platform-CA client certificate — every
 *    caller on this port is mutually authenticated (mesh posture), including
 *    health probes which present their mesh certs;
 *  - every request (except healthz + the mTLS-verified registry endpoint)
 *    must carry the gateway's signed X-SME-Context header; the Spring
 *    Security filter installs a pre-authenticated principal whose
 *    authorities are the subject's CRUD permissions plus OAuth scopes;
 *  - controllers enforce @PreAuthorize permissions; the service layer
 *    re-validates against the decision logic (defense in depth).
 */
@Configuration
@lombok.RequiredArgsConstructor
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final RbacConfig cfg;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        byte[] secret = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(cfg.ctxSecretPath()));
        // /healthz (liveness) and /rbac/internal/** (registry; identity comes from
        // the mTLS client certificate) are served without a gateway context —
        // same exemption surface as the Go admin plane.
        GatewayContextFilter ctxFilter = new GatewayContextFilter(secret, cfg.ctxSkewSeconds(), "/healthz", "/rbac/internal/");
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/healthz").permitAll()
                        .requestMatchers("/rbac/internal/**").permitAll() // identity enforced in-controller (cert CN)
                        .anyRequest().authenticated())
                .addFilterAt(ctxFilter, RequestHeaderAuthenticationFilter.class);
        return http.build();
    }

    /** Tomcat TLS-only listener with required client certs, built from PEMs. */
    @Bean
    public WebServerFactoryCustomizer<org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory> tomcatTls() {
        return factory -> {
            try {
            String listen = cfg.adminListen();
            int port = listen.contains(":")
                    ? Integer.parseInt(listen.substring(listen.lastIndexOf(':') + 1))
                    : Integer.parseInt(listen);
            factory.setPort(port);
            SslStoreBundle stores = SslStoreBundle.of(
                    PemKeyStore.keyStore(cfg.tlsCert(), cfg.tlsKey()), null,
                    PemKeyStore.trustStore(cfg.internalCA()));
            SslBundle bundle = SslBundle.of(stores, SslBundleKey.NONE,
                    SslOptions.of(null, new String[]{"TLSv1.3", "TLSv1.2"}), "TLS");
            org.springframework.boot.ssl.DefaultSslBundleRegistry registry =
                    new org.springframework.boot.ssl.DefaultSslBundleRegistry();
            registry.registerBundle("mesh", bundle);
            // Boot 4: the factory's `ssl` property is NOT a live object; callers must
            // SET it (getter returns null unless server.ssl.* was configured)
            var ssl = new org.springframework.boot.web.server.Ssl();
            ssl.setEnabled(true);
            ssl.setBundle("mesh");
            ssl.setClientAuth(org.springframework.boot.web.server.Ssl.ClientAuth.NEED);
            ssl.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            factory.setSslBundles(registry);
            factory.setSsl(ssl);
            } catch (Exception e) {
                throw new IllegalStateException("mesh TLS setup failed: " + e.getMessage(), e);
            }
        };
    }
}
