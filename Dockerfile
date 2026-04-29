# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -q -DskipTests

# Stage 2: Run
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/server-app-1.0.0-SNAPSHOT.jar app.jar

# Data directory inside the container (mapped to host via volume)
ENV APP_DATA_DIR=/data

EXPOSE 8080

ENTRYPOINT ["java", "-Dapp.data.dir=/data", "-Dserver.port=8080", "-jar", "app.jar"]
