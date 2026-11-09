# Multi-stage Gradle build for the MiPay SME backend (unirepo/backend).
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /workspace

# cache dependencies first: only settings/wrapper/build script changes invalidate this layer
COPY settings.gradle.kts gradlew gradlew.bat* ./
COPY gradle ./gradle
COPY build.gradle.kts ./
RUN gradle dependencies --no-daemon -q || true

COPY src ./src
RUN gradle clean bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/sme-backend.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-XX:+UseG1GC","-jar","/app/app.jar"]
