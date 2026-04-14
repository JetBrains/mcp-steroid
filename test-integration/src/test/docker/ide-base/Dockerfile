FROM registry.jetbrains.team/p/ij/docker-hub/debian:bookworm-slim

# Install system dependencies: Xvfb, window manager, fonts, utils
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        xvfb \
        x11-utils \
        fluxbox \
        fonts-dejavu \
        fonts-jetbrains-mono \
        ffmpeg \
        scrot \
        feh \
        imagemagick \
        curl \
        git \
        openssh-client \
        jq \
        ripgrep \
        unzip \
        rsync \
        procps \
        ca-certificates \
        gnupg \
        libxi6 \
        libxrender1 \
        libxtst6 \
        libxext6 \
        libx11-6 \
        libfreetype6 \
        libfontconfig1 \
        xdotool \
        xclip \
        xterm \
        locales \
    && rm -rf /var/lib/apt/lists/*

# Generate UTF-8 locale so xterm and CLI tools render non-ASCII correctly
RUN sed -i 's/# en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen && locale-gen
ENV LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8

# Install JDK 8, 11, 17, 21, 25 (Eclipse Temurin via Adoptium APT)
# Multiple versions ensure IntelliJ can detect a system JDK regardless of which candidate
# path it searches (amd64/arm64 arch suffix, exact version), and make multi-version
# SDK selection available to AI agents working on the project.
RUN curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" \
        > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        temurin-8-jdk \
        temurin-11-jdk \
        temurin-17-jdk \
        temurin-21-jdk \
        temurin-25-jdk && \
    rm -rf /var/lib/apt/lists/*

# Install Node.js 20 (NodeSource APT)
RUN mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" \
        > /etc/apt/sources.list.d/nodesource.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/*

ARG CACHE_BUST=1

# Set Java 21 as the default JAVA_HOME (arch-agnostic symlink).
# Java 25 (the newest installed JDK) is not compatible with Groovy 2.x DSL
# used by some arena projects (e.g. microshop-18). Java 21 is LTS and widely supported.
# Note: apt package creates temurin-21-jdk-<arch>, not temurin-21-<arch>.
# The Maven wrapper validates JAVA_HOME/bin/java exists and does NOT follow symlinks,
# so the symlink target must be the exact directory name from the apt package.
RUN ARCH=$(dpkg --print-architecture) && \
    ln -sfn /usr/lib/jvm/temurin-21-jdk-${ARCH} /usr/lib/jvm/java-21-default
ENV JAVA_HOME=/usr/lib/jvm/java-21-default

# Install Docker CLI for Testcontainers (Docker-in-Docker) support.
# Installs docker-ce-cli from Docker's official apt repo and adds the `agent`
# user to the `docker` group so it can access a mounted /var/run/docker.sock.
RUN install -m 0755 -d /etc/apt/keyrings && \
    curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc && \
    chmod a+r /etc/apt/keyrings/docker.asc && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/debian bookworm stable" \
        > /etc/apt/sources.list.d/docker.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends docker-ce-cli && \
    rm -rf /var/lib/apt/lists/*

# Install Chromium for puppeteer (used by JHipster and similar frontend projects).
# puppeteer's built-in download does not support arm64; installing system chromium
# and pointing PUPPETEER_EXECUTABLE_PATH at it avoids the "chromium binary is not
# available for arm64" error during `npm install`.
RUN apt-get update && \
    apt-get install -y --no-install-recommends chromium && \
    rm -rf /var/lib/apt/lists/*
ENV PUPPETEER_SKIP_DOWNLOAD=true
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium

# Install AI agent CLIs for integration tests
RUN npm install -g @anthropic-ai/claude-code
RUN npm install -g @openai/codex
RUN npm install -g @google/gemini-cli

# Create docker group (docker-ce-cli doesn't create it; only the full daemon does)
# and add non-root user to it so the agent can reach the mounted Docker socket.
RUN groupadd docker && \
    useradd -m -s /bin/bash agent && \
    usermod -aG docker agent && \
    mkdir -p /home/agent/.local/bin && \
    mkdir -p /home/agent/.codex && \
    mkdir -p /home/agent/.gemini && \
    chown -R agent:agent /home/agent

# Shared runtime helpers
COPY video-server.js /usr/local/bin/video-server.js
COPY wallpaper.jpg /usr/share/images/mcp-steroid-wallpaper.jpg

USER agent
WORKDIR /home/agent

ENV PATH="/home/agent/.local/bin:/usr/local/bin:/usr/bin:$PATH"
ENV SHELL=/bin/bash
ENV HOME=/home/agent

CMD ["sleep", "infinity"]
