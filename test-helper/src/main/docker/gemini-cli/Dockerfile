FROM registry.jetbrains.team/p/ij/docker-hub/debian:bookworm-slim

# Install Node.js 20 + Eclipse Temurin 21 JRE. The JRE lets the npx-kt MCP
# proxy (Kotlin/JVM, jvmToolchain(21)) run as a stdio MCP under the agent's
# launcher — used by :npx-kt:integrationTest's CliGeminiIntegrationTest.
# Adoptium APT mirrors the test-integration ide-base pattern.
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl ca-certificates gnupg && \
    mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | \
        gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" \
        > /etc/apt/sources.list.d/adoptium.list && \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | \
        gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" \
        > /etc/apt/sources.list.d/nodesource.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends nodejs temurin-21-jre && \
    rm -rf /var/lib/apt/lists/*

# Install Gemini CLI
RUN npm install -g @google/gemini-cli

# Create non-root user
RUN useradd -m -s /bin/bash agent
USER agent
WORKDIR /home/agent

CMD ["sleep", "infinity"]
