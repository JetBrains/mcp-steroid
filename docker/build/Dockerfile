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

# Install JDK 21 (Eclipse Temurin via Adoptium APT)
RUN curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" \
        > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends temurin-21-jdk && \
    rm -rf /var/lib/apt/lists/*

ARG CACHE_BUST=1

RUN ARCH=$(dpkg --print-architecture) && \
    ln -sfn /usr/lib/jvm/temurin-21-jdk-${ARCH} /usr/lib/jvm/java-21-default
ENV JAVA_HOME=/usr/lib/jvm/java-21-default

CMD ["sleep", "infinity"]
