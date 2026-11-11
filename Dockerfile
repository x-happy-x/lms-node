FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-venv curl aria2 ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /downloads

COPY target/lms-node-*.jar /app/lms-node.jar
COPY db /app/db
COPY scripts/install-ytdlp.sh /tmp/install-ytdlp.sh
COPY requirements-ytdlp-network.txt /tmp/ytdlp-network-requirements.txt
RUN sh /tmp/install-ytdlp.sh && rm /tmp/install-ytdlp.sh /tmp/ytdlp-network-requirements.txt

EXPOSE 8080
VOLUME ["/downloads"]

ENTRYPOINT ["java", "-Djdk.http.auth.tunneling.disabledSchemes=", "-Dspring.jmx.enabled=false", "-jar", "/app/lms-node.jar"]
