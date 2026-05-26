FROM registry.jetbrains.team/p/ij/docker-hub/debian:bookworm-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl \
        git \
        ca-certificates \
        gnupg \
        rsync \
        unzip \
    && rm -rf /var/lib/apt/lists/*

# Install JDK 25 (Eclipse Temurin via Adoptium APT).
# Gradle daemon is pinned to JDK 25 in gradle/gradle-daemon-jvm.properties to
# match IntelliJ IDEA 2026.1 (build 261), whose bundled JBR is JDK 25. The CI
# image must ship that JDK so `./gradlew buildPluginOnCI` doesn't have to fall
# back to foojay-downloading a toolchain inside the no-daemon container on
# every run (slow + needs network even for incremental builds).
RUN curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" \
        > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends temurin-25-jdk && \
    rm -rf /var/lib/apt/lists/*

ARG CACHE_BUST=1

RUN ARCH=$(dpkg --print-architecture) && \
    ln -sfn /usr/lib/jvm/temurin-25-jdk-${ARCH} /usr/lib/jvm/java-25-default
ENV JAVA_HOME=/usr/lib/jvm/java-25-default

CMD ["sleep", "infinity"]
