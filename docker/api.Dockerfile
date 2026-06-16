FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :api:bootJar --no-daemon -q

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /workspace/api/build/libs/api-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
