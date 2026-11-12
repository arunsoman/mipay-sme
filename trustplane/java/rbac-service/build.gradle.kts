// RBAC service: decision plane (gRPC over mTLS, same proto as the Go
// gateway) + admin REST plane protected by Spring Security.

plugins {
    id("org.springframework.boot")
    id("com.google.protobuf")
}

val grpc: String = property("grpcVersion") as String
val protobuf: String = property("protobufVersion") as String
val boot: String = property("springBootVersion") as String

dependencies {
    // Spring Boot 4 BOM (curated Spring Framework 7 / Security 7 / Jackson 3 versions)
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$boot"))

    implementation(project(":shared-lib"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.yaml:snakeyaml")

    // decision plane: gRPC over HTTP/2 + mTLS (wire-compatible with the Go gateway)
    implementation("io.grpc:grpc-netty-shaded:$grpc") // bundles boringssl for client-auth TLS
    implementation("io.grpc:grpc-stub:$grpc")
    implementation("io.grpc:grpc-protobuf:$grpc")
    implementation("com.google.protobuf:protobuf-java:$protobuf")
    implementation("com.google.protobuf:protobuf-java-util:$protobuf")
    // grpc-java generated sources reference javax.annotation.Generated
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:$boot"))
    annotationProcessor("org.projectlombok:lombok")

    // tests run the store, decisions and access rules against a scratch Postgres (TEST_RBAC_DSN)
    // JUnit 5.12 pinned for tests (strictly: wins over the Boot BOM's JUnit line)
    for (m in listOf("junit-jupiter", "junit-jupiter-api", "junit-jupiter-engine", "junit-jupiter-params")) {
        testImplementation("org.junit.jupiter:$m") { version { strictly("5.12.2") } }
    }
    for (m in listOf("junit-platform-launcher", "junit-platform-engine", "junit-platform-commons")) {
        testImplementation("org.junit.platform:$m") { version { strictly("1.12.2") } }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // This export utility is run explicitly with ROLE_PACK_DSN and ROLE_PACK_OUT. Keeping it
    // out of ordinary test runs prevents an intentional JUnit skip from hiding test coverage.
    if (System.getenv("ROLE_PACK_DSN").isNullOrBlank() || System.getenv("ROLE_PACK_OUT").isNullOrBlank()) {
        exclude("**/RolePackExport.class")
    }
    environment("TEST_RBAC_DSN", System.getenv("TEST_RBAC_DSN") ?: "")
    // CI must exercise the Postgres-backed policy tests instead of accepting JUnit skips.
    environment("REQUIRE_RBAC_DSN", System.getenv("REQUIRE_RBAC_DSN") ?: if (System.getenv("CI") == "true") "true" else "")
    // the engine tests keep the pre-2026-11-12 grid; the product seed is tested by RootRoleSeedTest
    environment("TEST_RBAC_SEED", file("src/test/resources/grid.engine-test.yaml").absolutePath)
    environment("TEST_RBAC_PRODUCT_SEED", rootProject.file("../config/rbac/grid.yaml").absolutePath)
    // role pack export (RolePackExport): skipped unless both are set
    environment("ROLE_PACK_DSN", System.getenv("ROLE_PACK_DSN") ?: "")
    environment("ROLE_PACK_OUT", System.getenv("ROLE_PACK_OUT") ?: "")
    environment("ROLE_PACK_ENV", System.getenv("ROLE_PACK_ENV") ?: "dev")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

sourceSets {
    main {
        java {
            srcDir(layout.buildDirectory.dir("generated/source/proto/main/java"))
            srcDir(layout.buildDirectory.dir("generated/source/proto/main/grpc"))
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobuf"
    }
    // grpc-java codegen executable (resolved from Maven Central artifacts)
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpc"
        }
    }
    generateProtoTasks {
        // 0.9.5 automatically applies plugins{} locators to every task
    }
}

springBoot {
    mainClass = "trustplane.rbac.RbacApplication"
}
