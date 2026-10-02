# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system --gid 1001 dashboard \
    && useradd --system --uid 1001 --gid dashboard --home /app --shell /usr/sbin/nologin dashboard \
    && mkdir /data \
    && chown dashboard:dashboard /data
WORKDIR /app
COPY --from=build --chown=dashboard:dashboard /src/build/libs/k8s-dashboard.jar /app/k8s-dashboard.jar
USER 1001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0" \
    SERVER_PORT=8080 \
    DASHBOARD_CLUSTER_DEMO_ENABLED=true \
    DASHBOARD_CLUSTER_LOAD_DEFAULT_KUBECONFIG=false \
    DASHBOARD_CLUSTER_DATA_DIR=/data
VOLUME /data
ENTRYPOINT ["java", "-jar", "/app/k8s-dashboard.jar"]
