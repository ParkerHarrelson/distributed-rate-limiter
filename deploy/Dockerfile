# syntax=docker/dockerfile:1
# Stage 1: build both jars with the Gradle wrapper. Dependency resolution is
# done in its own layer so source edits do not re-download the world.
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY core/build.gradle.kts core/
COPY server/build.gradle.kts server/
COPY loadgen/build.gradle.kts loadgen/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -q :core:dependencies :server:dependencies :loadgen:dependencies >/dev/null 2>&1 || true
COPY core core
COPY server server
COPY loadgen loadgen
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -q :server:bootJar :loadgen:jar -x test

# Stage 2: JRE only.
FROM eclipse-temurin:25-jre-alpine
RUN apk add --no-cache curl && adduser -D -u 10001 app
WORKDIR /app
COPY --from=build /src/server/build/libs/server.jar /src/loadgen/build/libs/loadgen.jar /app/
COPY configs/limits.yaml /app/configs/limits.yaml
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
