plugins {
    java
    id("org.springframework.boot") version "3.5.4"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.mipay"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Lombok: annotation processor only - lombok.jar never goes on the runtime classpath
    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")
    testCompileOnly("org.projectlombok:lombok:1.18.38")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.38")

    // Spring Boot starters (versions managed by the Boot plugin)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Migrations
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Databases
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // OpenAPI / Swagger UI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")

    // Tests
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // UAT acceptance is executed against a UTC deployment clock (UAT SB daily windows,
    // TC-RCN/TC-OPS-13 settlement days are UTC dates). Pinning the test JVM to UTC keeps
    // the pack deterministic regardless of the host timezone (documented in
    // docs/UAT-BACKEND-TEST-PACK.md, finding #1 notes the IST-zone product issue).
    jvmArgs("-Duser.timezone=UTC")
    // The race-condition detectors (TUat15) drive 8 concurrent service stacks against the
    // in-memory H2 MVStore plus synthetic history; the default 512 MiB heap OOM-kills the
    // test JVM (empty XML reports). 2 GiB keeps the full pack, races and load run in one JVM.
    maxHeapSize = "2g"
    // one JVM per Gradle test task is fine; keep results complete even if a race detector hangs:
    // Gradle kills the workers and still writes the XML files when a test exceeds the timeout
    systemProperty("junit.jupiter.execution.timeout.default", "240s")
    // Real-mesh integration tests (AuthOutbox*IT): TrustplaneAuthProvider speaks real mTLS HTTPS
    // to the Go IdP; MeshTls reads PEM paths from these env vars, defaulting to the PKI copied
    // from the running mesh volume (docker cp trustplane-idp-1:/pki → backend/build/test-pki).
    val meshCert = layout.buildDirectory.file("test-pki/_vol/mipay-mesh.pem").map { it.asFile.absolutePath }
    val meshKey = layout.buildDirectory.file("test-pki/_vol/mipay-mesh-key.pem").map { it.asFile.absolutePath }
    val meshCa = layout.buildDirectory.file("test-pki/_vol/platform-ca.pem").map { it.asFile.absolutePath }
    environment("MESH_CERT", System.getenv("MESH_CERT") ?: meshCert.get())
    environment("MESH_KEY", System.getenv("MESH_KEY") ?: meshKey.get())
    environment("MESH_CA", System.getenv("MESH_CA") ?: meshCa.get())
    // The IdP serves its certificate for the mesh hostname; host-side ITs connect over the
    // 127.0.0.1:18443 socat forwarder, so hostname verification must not use 127.0.0.1.
    systemProperty("jdk.internal.httpclient.disableHostnameVerification", "true")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "sme-backend.jar"
}
