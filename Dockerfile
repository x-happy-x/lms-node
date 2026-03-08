FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw

# Cache dependencies first
RUN ./mvnw -q -DskipTests dependency:go-offline

COPY src src
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends yt-dlp aria2 ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /downloads

COPY --from=build /build/target/lms-node-*.jar /app/lms-node.jar

EXPOSE 8080
VOLUME ["/downloads"]

ENTRYPOINT ["java", "-Dspring.jmx.enabled=false", "-jar", "/app/lms-node.jar"]
