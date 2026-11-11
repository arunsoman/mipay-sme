// Root of the trustplane Java 21 / Spring Boot estate
//   shared-lib    : gateway-context security, CRUD permission annotations,
//                   PEM/TLS plumbing, permission auto-registrar
//   rbac-service  : decision plane (gRPC/mTLS) + admin REST (Spring Security)

allprojects {
    group = "trustplane"
    version = "1.0.0"
}

subprojects {
    apply(plugin = "java-library")
    repositories {
        mavenCentral()
    }
    extensions.configure<JavaPluginExtension>("java") {
        // hard Java 21 across the estate
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-parameters")
        options.encoding = "UTF-8"
    }
}
