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
