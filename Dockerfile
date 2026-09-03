FROM eclipse-temurin:25-jdk@sha256:e787e08ef76f4c16866108cd7f9fcd96a68eef3ac6cc76866897d4d02d5a2262 AS builder

WORKDIR /workspace

COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY src ./src

RUN ./gradlew --no-daemon ociArtifact \
    && mkdir -p build/oci-root \
    && tar -xf build/distributions/vigilant-*.tar --strip-components=1 -C build/oci-root

FROM eclipse-temurin:25-jre@sha256:f9e65324a37f28209ce7dd0e5149a7aa954520ed936fb87813cf6ded2400a112

LABEL org.opencontainers.image.title="Vigilant" \
      org.opencontainers.image.description="Transparent guardrails gateway for AI agent platforms"

RUN groupadd --gid 10001 vigilant \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent \
        --shell /usr/sbin/nologin vigilant \
    && mkdir -p /etc/vigilant

WORKDIR /opt/vigilant

COPY --from=builder --chown=10001:10001 /workspace/build/oci-root/ ./

USER 10001:10001

EXPOSE 8080
STOPSIGNAL SIGTERM

ENTRYPOINT ["/opt/vigilant/bin/vigilant"]
