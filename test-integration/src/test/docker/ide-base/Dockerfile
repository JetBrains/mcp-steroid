# Pinned to the multi-arch index digest (linux/amd64 + linux/arm64) so BuildKit
# resolves the base from the local store instead of re-querying Docker Hub for the
# bookworm-slim tag on every build — that tag→digest round-trip is what stalls the
# "[internal] load metadata for docker.io/library/debian:bookworm-slim" step on a
# slow/rate-limited connection. Bump the digest deliberately to take a newer base.
FROM debian:bookworm-slim@sha256:0104b334637a5f19aa9c983a91b54c89887c0984081f2068983107a6f6c21eeb

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

# Install JDK 8, 11, 17, 21, 24, 25 (Eclipse Temurin via Adoptium APT)
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
        temurin-24-jdk \
        temurin-25-jdk && \
    rm -rf /var/lib/apt/lists/*

# Install Node.js 20 from the official nodejs.org release tarball.
# NodeSource's APT repo (deb.nodesource.com) answers 403 to data-centre egress
# addresses — including the AWS-hosted CI agents — which fails the image build at
# this layer while every other endpoint in this file (deb.debian.org,
# packages.adoptium.net, download.docker.com) stays reachable. The release tarball
# needs no third-party apt repo and no GPG key, and it carries npm/npx/corepack.
# Both digests are from https://nodejs.org/dist/v20.20.2/SHASUMS256.txt — bump the
# version and BOTH digests together to move Node forward.
ARG NODE_VERSION=20.20.2
ARG NODE_SHA256_AMD64=19e56f0825510207dd904f087fe52faa0a4eb6b2aab5f0ea7a33830d04888b8b
ARG NODE_SHA256_ARM64=47ef73d543ecf6eb19435f6c03a0ac4809b3bf0dd6b26c7c571efc2a6572a74d
RUN set -eu; \
    case "$(dpkg --print-architecture)" in \
        amd64) node_arch=x64;   node_sha256="$NODE_SHA256_AMD64" ;; \
        arm64) node_arch=arm64; node_sha256="$NODE_SHA256_ARM64" ;; \
        *) echo "Unsupported architecture: $(dpkg --print-architecture)" >&2; exit 1 ;; \
    esac; \
    tarball="node-v${NODE_VERSION}-linux-${node_arch}.tar.gz"; \
    curl -fsSLO "https://nodejs.org/dist/v${NODE_VERSION}/${tarball}"; \
    echo "${node_sha256}  ${tarball}" | sha256sum -c -; \
    tar -xzf "${tarball}" -C /usr/local --strip-components=1 \
        --exclude CHANGELOG.md --exclude LICENSE --exclude README.md; \
    rm "${tarball}"; \
    node --version; \
    npm --version

RUN ARCH=$(dpkg --print-architecture) && \
    ln -sfn /usr/lib/jvm/temurin-25-jdk-${ARCH} /usr/lib/jvm/java-25-default
ENV JAVA_HOME=/usr/lib/jvm/java-25-default

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

# --- Daily refresh boundary -------------------------------------------------
# CACHE_BUST is wired to the current date by buildDockerImage(), so every layer
# BELOW this line rebuilds once per day to pick up the latest AI agent releases.
# Everything ABOVE (system apt packages, JDKs, Node, Docker CLI, Chromium) stays
# cached across days — those are slow, network-heavy, and effectively never
# change. Keep the cheap, volatile steps below this line; keep the expensive,
# stable steps above it.
#
# The explicit `echo "$CACHE_BUST"` forces the invalidation under BOTH BuildKit
# and the legacy builder: a declared-but-unreferenced ARG does not bust the
# cache on the legacy builder, so referencing it makes the behavior portable.
ARG CACHE_BUST=1
RUN echo "agent CLI cache-bust token: $CACHE_BUST"

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
