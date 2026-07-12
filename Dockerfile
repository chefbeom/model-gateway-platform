FROM gradle:8.10.2-jdk17 AS build
WORKDIR /workspace
COPY settings.gradle build.gradle ./
COPY src ./src
COPY docs ./docs
RUN gradle bootJar --no-daemon

# Reproducible test target for hosts that do not have a local Gradle wrapper.
# Run: docker build --target test .
FROM build AS test
RUN gradle test --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/aiconnect-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
