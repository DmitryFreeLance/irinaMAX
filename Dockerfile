FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /app/data
COPY --from=build /build/target/max-order-bot-1.0.0.jar /app/app.jar
ENV SQLITE_PATH=/app/data/bot.db
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
