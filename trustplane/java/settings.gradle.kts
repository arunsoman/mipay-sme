pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        // Spring Boot 4.x (Spring Framework 7, Jakarta EE 11, Jackson 3)
        id("org.springframework.boot") version "4.1.2"
        id("com.google.protobuf") version "0.9.4"
    }
}

rootProject.name = "trustplane-java"

include("shared-lib", "rbac-service")
