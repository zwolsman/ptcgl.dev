FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :app:bootJar --no-daemon -q

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /workspace/app/build/libs/app-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
CMD ["--plan"]
