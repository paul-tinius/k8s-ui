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

# Trust the LM combined CA bundle, pulled fresh at build time, plus any CA
# certificate dropped into certs/ (see certs/README.md). Needed for LDAPS
# logins against the LM Active Directory - without the AD CA in the JRE's
# trust store, every login fails with an SSL handshake error.
## UNCOMMENT WHEN USING INSIDE LM NETWORK
# COPY certs/ /tmp/certs/
# RUN set -e; \
#     apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/*; \
#     curl -fsSL https://crl.external.lmco.com/trust/pem/combined/ -o /tmp/certs/lm-combined.pem; \
#     awk '/-----BEGIN CERTIFICATE-----/{n++} {print > ("/tmp/certs/lm-combined-" n ".pem")}' /tmp/certs/lm-combined.pem; \
#     rm -f /tmp/certs/lm-combined.pem; \
#     for cert in /tmp/certs/*.pem; do \
#       [ -f "$cert" ] || continue; \
#       keytool -importcert -noprompt -trustcacerts \
#         -alias "$(basename "$cert" .pem)" \
#         -file "$cert" \
#         -keystore "$JAVA_HOME/lib/security/cacerts" \
#         -storepass changeit; \
#     done; \
#     rm -rf /tmp/certs; \
#     apt-get purge -y curl && apt-get autoremove -y

USER 1001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0" \
    SERVER_PORT=8080 \
    DASHBOARD_CLUSTER_DEMO_ENABLED=true \
    DASHBOARD_CLUSTER_LOAD_DEFAULT_KUBECONFIG=false \
    DASHBOARD_CLUSTER_DATA_DIR=/data
VOLUME /data
ENTRYPOINT ["java", "-jar", "/app/k8s-dashboard.jar"]
