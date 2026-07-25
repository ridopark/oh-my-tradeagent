# STC close-intent /classify service.
# Torch-free: fastembed uses onnxruntime, so the image stays small and CPU-only.
# The ONNX weights are warmed into the image at build time — no first-request
# download in prod (the node is memory-tight and may have no egress).
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/opt/venv \
    # fastembed downloads + memory-maps the ONNX model from here. Baked into the
    # image at build time and made readable by the non-root runtime user below.
    FASTEMBED_CACHE_PATH=/opt/fastembed_cache

# uv: fast Python install; copy a pinned binary from its official image.
COPY --from=ghcr.io/astral-sh/uv:0.10.9 /uv /usr/local/bin/uv

WORKDIR /app/services/stc-intent-service

# Dependency layer first so source edits don't bust the (slow) sync cache.
COPY services/stc-intent-service/pyproject.toml services/stc-intent-service/uv.lock ./
RUN uv sync --frozen --no-dev

# Application source.
COPY services/stc-intent-service/ohmytradeagent_stc_intent ./ohmytradeagent_stc_intent

ENV PATH="/opt/venv/bin:${PATH}"

# Warm the model cache: construct a Classifier so fastembed downloads + caches the
# ONNX weights into FASTEMBED_CACHE_PATH. Make the cache world-readable so the
# non-root user can mmap it at runtime.
RUN mkdir -p "$FASTEMBED_CACHE_PATH" \
    && python -c "from ohmytradeagent_stc_intent.classifier import Classifier; Classifier()" \
    && chmod -R a+rX "$FASTEMBED_CACHE_PATH"

# Non-root runtime user.
RUN useradd --create-home --uid 10001 appuser
USER appuser

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health').status==200 else 1)" \
      || exit 1

ENTRYPOINT ["python", "-m", "ohmytradeagent_stc_intent.main"]
