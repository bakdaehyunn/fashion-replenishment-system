FROM gradle:9.2.1-jdk25 AS build
WORKDIR /app
COPY . .
ARG MODULE
RUN ./gradlew --no-daemon ":${MODULE}:bootJar" && cp "${MODULE}"/build/libs/*.jar /tmp/app.jar
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /tmp/app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
