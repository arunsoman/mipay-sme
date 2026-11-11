// Library consumed by every Java service: gateway-context Spring Security
// filter, CRUD permission annotations + auto-registrar, PEM keystores.

plugins {
    `java-library`
}

val boot: String = property("springBootVersion") as String

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:$boot"))

    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-security")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:$boot"))
    annotationProcessor("org.projectlombok:lombok")
}

// plain library jar (no Spring Boot fat jar)
tasks.withType<Jar>().configureEach {
    enabled = true
}
