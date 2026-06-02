# Sidecar runtime: Playwright base image (Python + Chromium + glibc deps preinstalled).
# Replica >= 1 supported; durable dedupe is Temporal's job.
FROM mcr.microsoft.com/playwright/python:v1.59.0-noble

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/opt/venv

# uv: fast Python install; copy a pinned binary from its official image.
COPY --from=ghcr.io/astral-sh/uv:0.10.9 /uv /usr/local/bin/uv

WORKDIR /app

# Bring contract/python in first so its hash anchors the lock layer.
COPY contract/python /app/contract/python
COPY services/signal-source-discord/pyproject.toml services/signal-source-discord/uv.lock /app/services/signal-source-discord/

WORKDIR /app/services/signal-source-discord
RUN uv sync --frozen --no-dev

# Now the application source. Layer ordering keeps deps cached between source edits.
COPY services/signal-source-discord/ohmytradeagent_sidecar /app/services/signal-source-discord/ohmytradeagent_sidecar

# The state volume mounts at /app/state. Create it owned by pwuser *before*
# dropping privileges: Docker copies this directory's ownership onto a fresh
# named volume on first mount, so the non-root runtime user can write
# storage_state.json / heartbeat. Without it, a fresh local volume is root-owned
# and both the bootstrap and the watcher fail with EACCES. (k8s sidesteps this
# via fsGroup; a plain docker-compose volume does not.)
RUN mkdir -p /app/state && chown pwuser:pwuser /app/state

# Non-root user; Playwright's base image already provisions one named "pwuser".
USER pwuser

ENV PATH="/opt/venv/bin:${PATH}"
ENV STATE_DIR=/app/state

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD test -f "$STATE_DIR/heartbeat" && \
      test $(( $(date +%s) - $(stat -c %Y "$STATE_DIR/heartbeat") )) -lt 120 \
      || exit 1

ENTRYPOINT ["python", "-m", "ohmytradeagent_sidecar.main"]
