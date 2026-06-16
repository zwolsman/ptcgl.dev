FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :sync:bootJar --no-daemon -q

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /workspace/sync/build/libs/sync-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
CMD ["--sync"]
