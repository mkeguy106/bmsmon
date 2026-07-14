# Base images digest-pinned to the multi-arch index (SEC-10); tag kept for readability.
# Refresh: docker manifest HEAD (docker-content-digest) or `docker buildx imagetools inspect`.

# --- web build ---
FROM node:20-alpine@sha256:fb4cd12c85ee03686f6af5362a0b0d56d50c58a04632e6c0fb8363f609372293 AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
# playwright is a local-only smoke-test devDep; never pull its browsers in CI.
RUN PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm ci
COPY web/ ./
RUN npm run build

# --- api runtime ---
FROM python:3.12-slim@sha256:423ed6ab25b1921a477529254bfeeabf5855151dc2c3141699a1bfc852199fbf
WORKDIR /app
# Pinned runtime dependency closure (SEC-10/SRV-12) — see server/requirements.lock.
COPY server/requirements.lock ./
RUN pip install --no-cache-dir -r requirements.lock
# The app package is imported from /app via an explicit PYTHONPATH (the project itself
# is not pip-installed: the old bare `pip install .` shipped zero code — pyproject has
# no package config — and imports only worked through uvicorn's implicit CWD lookup).
COPY server/app ./app
COPY --from=web /web/dist ./web/dist
ENV BMSMON_WEB_DIST=/app/web/dist PYTHONPATH=/app
# Non-root runtime (SEC-11): nothing needs root — static assets are read-only and
# uvicorn binds unprivileged port 8000.
RUN useradd --system --no-create-home --uid 10001 app
USER app
EXPOSE 8000
# Mirrors the compose healthcheck; python urllib because slim has no curl. urlopen
# raises on any non-2xx/timeout, failing the check.
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/api/v1/health', timeout=4)"]
# SINGLE WORKER ONLY — do not add --workers: the jti replay cache (app/auth/device_jwt.py)
# and the live WS bus (app/live/bus.py) are process-local; >1 worker silently breaks
# replay protection and live updates.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
