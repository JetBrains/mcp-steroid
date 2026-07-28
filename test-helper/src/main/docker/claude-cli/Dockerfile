FROM debian:bookworm-slim

# Install Eclipse Temurin 25 JRE. The JRE lets the devrig MCP
# devrig (Kotlin/JVM, jvmToolchain(25) — raised for the 262 EAP work) run
# as a stdio MCP under the agent's launcher — used by :npx-kt:integrationTest's
# CliClaudeIntegrationTest. Adoptium APT mirrors the test-integration ide-base
# pattern.
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl ca-certificates gnupg && \
    mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | \
        gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" \
        > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends temurin-25-jre && \
    rm -rf /var/lib/apt/lists/*

# Install Node.js 20 from the official nodejs.org release tarball — NodeSource's APT repo
# (deb.nodesource.com) answers 403 to data-centre egress addresses, so it cannot be used
# from CI. Same step as test-integration's ide-base image; digests are from
# https://nodejs.org/dist/v20.20.2/SHASUMS256.txt — bump the version and BOTH digests
# together to move Node forward.
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

# Install Claude Code CLI
RUN npm install -g @anthropic-ai/claude-code

# Create non-root user
RUN useradd -m -s /bin/bash agent
USER agent
WORKDIR /home/agent

CMD ["sleep", "infinity"]
