# syntax=docker/dockerfile:1
# Build context is the repo root (see docker-compose.yml).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
# Cache the Gradle home (dependencies + wrapper dist) across builds.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :zelo-server:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 zelo
WORKDIR /app
COPY --from=build /workspace/zelo-server/build/libs/zelo-server-*.jar app.jar
USER zelo
EXPOSE 8080
# Long-running JVM hygiene for the always-on box (SaaS playbook): the default
# 128m metaspace OOMs over days; fail fast on OOM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxMetaspaceSize=320m -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "app.jar"]
