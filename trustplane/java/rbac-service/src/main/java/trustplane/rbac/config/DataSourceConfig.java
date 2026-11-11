package trustplane.rbac.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import trustplane.rbac.Env;
import trustplane.shared.PostgresDsn;

import javax.sql.DataSource;
import java.util.Properties;

/** JDBC datasource from the Go-style postgres DSN env (RBAC_DSN). */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource() {
        PostgresDsn dsn = PostgresDsn.parse(Env.get("RBAC_DSN", ""));
        Properties props = new Properties();
        for (String kv : dsn.params().split("&")) {
            if (kv.isBlank()) continue;
            String[] pair = kv.split("=", 2);
            props.setProperty(pair[0], pair.length > 1 ? pair[1] : "");
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(dsn.jdbcUrl());
        ds.setUsername(dsn.user());
        ds.setPassword(dsn.password());
        ds.setDataSourceProperties(props);
        ds.setMaximumPoolSize(16);
        ds.setPoolName("rbac-pool");
        return ds;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
