# syntax=docker/dockerfile:1.6

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline -DskipTests
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S spine && adduser -S spine -G spine
WORKDIR /app
COPY --from=build /build/target/spine-lite-jvm-*.jar /app/app.jar
USER spine
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
