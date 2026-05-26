ARG BASE_IMAGE=mcp-steroid-ide-base-test
FROM ${BASE_IMAGE}

# We deliberately do NOT bake an IDE into this image — the whole point of
# the test is that `devrig backend download` fetches and installs one
# itself, into ~/.mcp-steroid/backends/. Everything the IDE needs at
# runtime (Xvfb, fluxbox, fonts, JBR-compatible libs, ffmpeg) is already
# in ide-base.

USER agent
WORKDIR /home/agent
