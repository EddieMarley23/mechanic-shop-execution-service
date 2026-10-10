FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /app
COPY . .
# The local Maven repository is kept in a BuildKit cache, so rebuilds do not download every
# dependency again.
RUN --mount=type=cache,target=/root/.m2 mvn -B clean package -DskipTests

# The New Relic Java agent is downloaded at build time instead of being vendored in git,
# so the repository stays free of binaries and the version is pinned in one place.
FROM alpine:3.20 AS newrelic-agent
ARG NEW_RELIC_AGENT_VERSION=9.4.0
RUN apk add --no-cache curl && \
    curl -fsSL -o /newrelic.jar \
      "https://repo1.maven.org/maven2/com/newrelic/agent/java/newrelic-agent/${NEW_RELIC_AGENT_VERSION}/newrelic-agent-${NEW_RELIC_AGENT_VERSION}.jar"

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring

COPY --from=newrelic-agent --chown=spring:spring /newrelic.jar /usr/local/newrelic/newrelic.jar
COPY --chown=spring:spring ./newrelic/newrelic.yml /usr/local/newrelic/newrelic.yml
COPY --from=builder --chown=spring:spring /app/target/mechanic-shop-execution-0.0.1-SNAPSHOT.jar app.jar

USER spring:spring

# Defaults for the agent. NEW_RELIC_LICENSE_KEY is intentionally absent: it is injected at
# runtime from a Kubernetes Secret (or the local .env file) and never baked into the image.
ENV NEW_RELIC_APP_NAME="mechanic-shop-execution" \
    NEW_RELIC_DISTRIBUTED_TRACING_ENABLED="true" \
    NEW_RELIC_APPLICATION_LOGGING_FORWARDING_ENABLED="true" \
    NEW_RELIC_LOG_FILE_NAME="STDOUT"

EXPOSE 8082

ENTRYPOINT ["java","-javaagent:/usr/local/newrelic/newrelic.jar","-jar","app.jar"]
