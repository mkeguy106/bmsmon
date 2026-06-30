# --- web build ---
FROM node:20-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json* ./
RUN npm ci || npm install
COPY web/ ./
RUN npm run build

# --- api runtime ---
FROM python:3.12-slim
WORKDIR /app
COPY server/pyproject.toml ./
RUN pip install --no-cache-dir .
COPY server/app ./app
COPY --from=web /web/dist ./web/dist
ENV BMSMON_WEB_DIST=/app/web/dist
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
