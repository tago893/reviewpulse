# syntax=docker/dockerfile:1.7
# Build with the repo's Maven Wrapper (Maven version + checksum pinned in .mvn/wrapper).
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
# Without unzip the wrapper falls back to the .tar.gz distribution, whose checksum differs from the
# pinned .zip checksum, and (correctly) refuses to run.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q package -DskipTests \
    && cp target/reviewpulse-backend-*.jar /app.jar

FROM eclipse-temurin:21.0.13_9-jre-noble
RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app /data/index /data/replay && chown -R app:app /app /data
WORKDIR /app
COPY --from=build --chown=app:app /app.jar /app/app.jar
USER app
# Inside the container the app must listen on all interfaces; Compose publishes it on 127.0.0.1 only.
ENV SERVER_ADDRESS=0.0.0.0 \
    REVIEWPULSE_SNAPSHOT_DIR=/data/index \
    REVIEWPULSE_REPLAY_DIR=/data/replay \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
