# Ubuntu with curl + unzip PRE-BAKED for the install.sh end-to-end lane (issue #443).
#
# These tools used to be apt-get-installed in the test container's ENTRYPOINT at test time,
# silenced to /dev/null — so whenever the runner's apt mirror stalled (2026-08-04: 11 consecutive
# CI failures across every branch), the container looked healthy while the readiness poll burned
# its whole deadline with zero diagnostics. Baking the tools into an image converts a mirror
# outage into a fast, fully-logged `docker build` failure, and BuildKit layer-caches the result
# on the agent so the apt round normally runs once, not once per test.
#
# Digest-pinned to the multi-arch index (docker buildx imagetools inspect ubuntu:24.04,
# 2026-08-05) so the base cannot drift underneath the tag.
FROM ubuntu:24.04@sha256:561618e2c15bf2397621dd04f96926663a3b5616c189cf7e38db7e82f5c538ea

# Bounded retry with VISIBLE output: a degraded (not dead) mirror deserves a second chance;
# a dead one must fail THIS build loudly, never hang a healthy-looking container.
RUN set -eux; \
    ok=""; \
    for attempt in 1 2 3; do \
        if apt-get update && apt-get install -y --no-install-recommends curl unzip ca-certificates; then ok=1; break; fi; \
        echo "apt-get attempt $attempt failed; retrying in 5s" >&2; \
        sleep 5; \
    done; \
    [ -n "$ok" ]; \
    rm -rf /var/lib/apt/lists/*; \
    command -v curl; \
    command -v unzip
