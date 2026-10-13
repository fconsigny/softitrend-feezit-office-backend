FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./
COPY src/ src/
RUN mvn --batch-mode --no-transfer-progress clean verify

FROM eclipse-temurin:25-jre AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 office \
    && useradd --uid 10001 --gid 10001 --no-log-init --home-dir /app --shell /usr/sbin/nologin office
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/feezit-office-backend-0.1.0-SNAPSHOT.jar /app/office.jar
ENV SERVER_ADDRESS=0.0.0.0 SERVER_PORT=8089 SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0 -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED"
USER 10001:10001
EXPOSE 8089
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD [ "$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 4 http://127.0.0.1:8089/office-api/session)" = 401 ] || exit 1
ENTRYPOINT ["java", "-jar", "/app/office.jar"]
