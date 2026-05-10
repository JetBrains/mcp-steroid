FROM debian:bookworm-slim

# Minimal Linux environment for running npx-kt's stdio MCP server end-to-end
# from integration tests. No agent CLI here — this image exists so the
# `CliMcpStdioStdoutCleanlinessTest` Docker variant can run the launcher
# unattended and assert stdout cleanliness on Linux from a Mac/Windows host.
#
# Eclipse Temurin 21 JRE matches the npx-kt build's `jvmToolchain(21)`. Same
# Adoptium APT pattern as `test-integration/src/test/docker/ide-base/Dockerfile`.
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ca-certificates curl gnupg && \
    mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | \
        gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" \
        > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends temurin-21-jre && \
    rm -rf /var/lib/apt/lists/*

# Non-root user mirrors the agent containers so file permissions match.
RUN useradd -m -s /bin/bash agent
USER agent
WORKDIR /home/agent

CMD ["sleep", "infinity"]
