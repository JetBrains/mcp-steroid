ARG BASE_IMAGE=mcp-steroid-ide-base-test
FROM ${BASE_IMAGE}

# --- Gradle cache pre-bake ---------------------------------------------------
# The in-container IDE Gradle sync of the test project used to cold-download the
# Gradle distribution + kotlin-gradle-plugin + dependencies on every run, blowing
# the import's 480s wait on slow agent egress (issue #412). Bake that state at
# image-build time by running the REAL test project's build (same wrapper version,
# same dependency coordinates by construction), then keep only the network-derived
# state (wrapper dists + caches/modules-2) as a seed dir.
#
# The seed cannot live at ~/.gradle: the test harness bind-mounts a host cache dir
# over /home/agent/.gradle at container start, shadowing anything baked there.
# IntelliJDriver.startIde rsyncs this seed into the mounted cache before the IDE
# launches (--ignore-existing, so an already-warm host cache stays authoritative).
#
# Placed BEFORE the IDE-archive layer so every IDE variant built from this
# Dockerfile (stable/eap/each compat version) shares ONE cached bake layer and an
# IDE bump never re-runs the multi-hundred-MB download.
USER agent
WORKDIR /home/agent
COPY --chown=agent:agent prebake-resolve.init.gradle /tmp/prebake-resolve.init.gradle
COPY --chown=agent:agent test-project /tmp/gradle-prebake-project
RUN cd /tmp/gradle-prebake-project && \
    sh gradlew --no-daemon --init-script /tmp/prebake-resolve.init.gradle testClasses prebakeResolveAll && \
    mkdir -p /home/agent/gradle-cache-seed/caches && \
    mv /home/agent/.gradle/wrapper /home/agent/gradle-cache-seed/wrapper && \
    mv /home/agent/.gradle/caches/modules-2 /home/agent/gradle-cache-seed/caches/modules-2 && \
    find /home/agent/gradle-cache-seed/wrapper/dists -name "*.zip" -delete && \
    rm -rf /home/agent/.gradle /tmp/gradle-prebake-project /tmp/prebake-resolve.init.gradle && \
    du -sh /home/agent/gradle-cache-seed

USER root

# Copy and extract IntelliJ IDEA archive (pre-downloaded by Gradle)
COPY ide.tar.gz /tmp/ide.tar.gz
RUN mkdir -p /opt/idea && chmod a+rwx /opt && \
    tar -xzf /tmp/ide.tar.gz -C /opt/idea --strip-components=1 && \
    rm /tmp/ide.tar.gz

USER agent
WORKDIR /home/agent

ENV PATH="/home/agent/.local/bin:/opt/idea/bin:/usr/local/bin:/usr/bin:$PATH"
ENV SHELL=/bin/bash
ENV HOME=/home/agent

CMD ["sleep", "infinity"]
