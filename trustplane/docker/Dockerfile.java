# syntax=docker/dockerfile:1
# Java service (RBAC decision/admin plane) — Java 21, Spring Boot 4, Gradle build
ARG MODULE

FROM gradle:8.14.3-jdk21 AS build
ARG MODULE
WORKDIR /src
COPY java/settings.gradle.kts java/gradle.properties java/build.gradle.kts java/gradlew ./
COPY java/gradle ./gradle
COPY java/shared-lib ./shared-lib
COPY java/rbac-service ./rbac-service
RUN --mount=type=cache,target=/home/gradle/.gradle \
    --mount=type=cache,target=/root/.gradle \
    gradle --no-daemon -q :${MODULE}:bootJar -x test

FROM eclipse-temurin:21-jre-jammy
ARG MODULE
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && useradd -r -u 10001 app
# NOTE: MODULE arg is consumed by build stage; jar name is deterministic
COPY --from=build --chmod=0555 /src/${MODULE}/build/libs/${MODULE}-1.0.0.jar /app/app.jar
USER 10001
EXPOSE 8443
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
