package trustplane.rbac;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RBAC service of the trustplane platform (Java 21 / Spring Boot Security).
 *
 * Decision plane : gRPC over mTLS (Check / ListPermissions / Watch) consumed
 *                  by the Go API gateway.
 * Admin plane    : Spring MVC REST API fronted by the gateway; every endpoint
 *                  declares CRUD+approve permissions that the RBAC service
 *                  itself registers automatically at boot.
 */
@SpringBootApplication
public class RbacApplication {
    public static void main(String[] args) {
        SpringApplication.run(RbacApplication.class, args);
    }
}
