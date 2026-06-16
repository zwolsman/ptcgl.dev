FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :api:bootJar --no-daemon -q

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /workspace/api/build/libs/api-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
