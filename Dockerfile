FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends yt-dlp aria2 ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /downloads

COPY target/lms-node-*.jar /app/lms-node.jar

EXPOSE 8080
VOLUME ["/downloads"]

ENTRYPOINT ["java", "-Dspring.jmx.enabled=false", "-jar", "/app/lms-node.jar"]
