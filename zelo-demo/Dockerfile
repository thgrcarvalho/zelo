# syntax=docker/dockerfile:1
# Build context is the repo root (see docker-compose.yml).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :zelo-demo:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre
RUN useradd -r -u 1001 zelo
WORKDIR /app
COPY --from=build /workspace/zelo-demo/build/libs/zelo-demo-*.jar app.jar
USER zelo
EXPOSE 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxMetaspaceSize=320m -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "app.jar"]
