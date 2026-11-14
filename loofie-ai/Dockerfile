# Loofie AI service image. Runs on any Linux server with an NVIDIA GPU (driver with CUDA 13
# support + NVIDIA Container Toolkit). torch's wheel brings its own CUDA libraries, so a slim
# Python base is enough. Build: docker compose --profile server build
FROM python:3.13-slim

COPY --from=ghcr.io/astral-sh/uv:0.11.3 /uv /uvx /bin/

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/models

WORKDIR /app

# Dependencies first, so code changes don't re-download torch.
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project

COPY app ./app
COPY config ./config
COPY migrations ./migrations
COPY alembic.ini README.md ./
RUN uv sync --locked --no-dev

RUN useradd --create-home --uid 1000 loofie && mkdir -p /models && chown -R loofie /app /models
USER loofie

ENV PATH="/app/.venv/bin:$PATH"
EXPOSE 8100
HEALTHCHECK --interval=30s --timeout=5s --start-period=600s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8100/health', timeout=4)"

# Apply any new migrations, then serve.
CMD ["sh", "-c", "alembic upgrade head && uvicorn app.api.main:app --host 0.0.0.0 --port 8100"]
