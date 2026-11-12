package trustplane.rbac;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RBAC service of the trustplane platform (Java 21 / Spring Boot Security).
 *
 * Decision plane : gRPC over mTLS (Check / ListPermissions / Watch) consumed
 *                  by the Go API gateway.
 * Admin plane    : Spring MVC REST API fronted by the gateway; every endpoint
 *                  binds one cell of the permission grid (row:verb), registered
 *                  with the endpoints it protects at boot.
 */
@SpringBootApplication
@EnableScheduling
public class RbacApplication {
    public static void main(String[] args) {
        SpringApplication.run(RbacApplication.class, args);
    }
}
