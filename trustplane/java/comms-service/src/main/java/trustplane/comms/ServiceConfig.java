package trustplane.comms;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleKey;
import org.springframework.boot.ssl.SslOptions;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import trustplane.shared.PemKeyStore;
import trustplane.shared.PostgresDsn;

@Configuration
public class ServiceConfig {
    @Bean SecurityFilterChain meshOnly(HttpSecurity http) throws Exception {
        // The HTTPS listener requires a client certificate. Controllers then restrict
        // event prefixes and contact writes by its verified subject CN.
        return http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
    @Bean
    DataSource dataSource() {
        PostgresDsn dsn = PostgresDsn.parse(CommsConfig.env("COMMS_DB_URL", ""));
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(dsn.jdbcUrl());
        ds.setUsername(dsn.user());
        ds.setPassword(dsn.password());
        Properties props = new Properties();
        for (String kv : dsn.params().split("&")) {
            if (kv.isBlank()) continue;
            String[] pair = kv.split("=", 2);
            props.setProperty(pair[0], pair.length == 2 ? pair[1] : "");
        }
        ds.setDataSourceProperties(props);
        ds.setMaximumPoolSize(8);
        return ds;
    }

    @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }

    @Bean Sealer sealer() throws Exception {
        return Sealer.load(Path.of(CommsConfig.env("COMMS_DATA_KEY_FILE", "/pki/comms-data-key")));
    }

    @Bean
    WebServerFactoryCustomizer<org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory> tomcatTls() {
        return factory -> {
            try {
                String listen = CommsConfig.env("COMMS_LISTEN", ":8443");
                factory.setPort(Integer.parseInt(listen.substring(listen.lastIndexOf(':') + 1)));
                SslStoreBundle stores = SslStoreBundle.of(
                        PemKeyStore.keyStore(CommsConfig.env("COMMS_TLS_CERT", "/pki/comms-server.pem"),
                                CommsConfig.env("COMMS_TLS_KEY", "/pki/comms-server-key.pem")), null,
                        PemKeyStore.trustStore(CommsConfig.env("COMMS_INTERNAL_CA", "/pki/platform-ca.pem")));
                SslBundle bundle = SslBundle.of(stores, SslBundleKey.NONE,
                        SslOptions.of(null, new String[]{"TLSv1.3", "TLSv1.2"}), "TLS");
                var registry = new org.springframework.boot.ssl.DefaultSslBundleRegistry();
                registry.registerBundle("mesh", bundle);
                var ssl = new org.springframework.boot.web.server.Ssl();
                ssl.setEnabled(true);
                ssl.setBundle("mesh");
                ssl.setClientAuth(org.springframework.boot.web.server.Ssl.ClientAuth.NEED);
                ssl.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                factory.setSslBundles(registry);
                factory.setSsl(ssl);
            } catch (Exception e) {
                throw new IllegalStateException("comms mesh TLS setup failed", e);
            }
        };
    }
}
