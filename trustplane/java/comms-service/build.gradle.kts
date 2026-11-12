plugins { id("org.springframework.boot") }

val boot: String = property("springBootVersion") as String
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$boot"))
    implementation(project(":shared-lib"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.yaml:snakeyaml")
    runtimeOnly("org.postgresql:postgresql")
    for (m in listOf("junit-jupiter", "junit-jupiter-api", "junit-jupiter-engine")) {
        testImplementation("org.junit.jupiter:$m") { version { strictly("5.12.2") } }
    }
    for (m in listOf("junit-platform-launcher", "junit-platform-engine", "junit-platform-commons")) {
        testImplementation("org.junit.platform:$m") { version { strictly("1.12.2") } }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

springBoot { mainClass = "trustplane.comms.CommsApplication" }
