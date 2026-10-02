import os
from dataclasses import dataclass, field


def _split(v: str) -> list[str]:
    return [s for s in (p.strip() for p in v.replace("|", ",").split(",")) if s]


@dataclass(frozen=True)
class Settings:
    database_url: str = os.environ.get(
        "DATABASE_URL", "postgresql://bmsmon:bmsmon@localhost:5432/bmsmon"
    )
    # SEC-13/SEC-20: bmsmon enforces Authentik group membership ITSELF, fail-closed, on
    # /web/* and /ws (auth/authentik.py authorize) instead of trusting that the Authentik
    # application binding is right; it had none until 2026-08-23. Exact, case-sensitive
    # match against X-Authentik-Groups; an empty value matches nobody.
    # Viewers may read the dashboard: live + historical GPS.
    viewer_group: str = os.environ.get(
        "BMSMON_VIEWER_GROUP", "Covert.Life - Full App Access - User Group"
    )
    # Admins may mint share links / API keys / enroll codes and revoke devices. OWNER ONLY,
    # never the household access group (SEC-20). An admin also counts as a viewer.
    admin_group: str = os.environ.get(
        "BMSMON_ADMIN_GROUP", "Covert.Life - bmsmon - Admin Group"
    )
    # Optional shared secret between the reverse proxy (Traefik) and the app. When set, every
    # /web/* request and the /ws handshake must carry an X-Bmsmon-Proxy-Secret header exactly
    # matching this value BEFORE any X-Authentik-* identity header is trusted — defense in depth
    # against anything that can reach the container directly and forge identity headers.
    # Empty (default) = feature off. To enable in prod: set BMSMON_PROXY_SECRET in the stack .env
    # AND configure Traefik to inject the header (middleware customRequestHeaders) on the
    # Authentik-routed router. /api/v1/* is unaffected (device-JWT auth).
    proxy_secret: str = os.environ.get("BMSMON_PROXY_SECRET", "")
    # Request-body caps. max_body_bytes caps EVERY route's body off the wire (one ASGI
    # middleware, app/middleware.py BodySizeLimitMiddleware — SEC-17); max_gunzip_bytes caps
    # the decompressed size of the gzipped device bodies (/api/v1/ingest, /config).
    max_body_bytes: int = int(os.environ.get("BMSMON_MAX_BODY_BYTES", str(1 * 1024 * 1024)))
    max_gunzip_bytes: int = int(os.environ.get("BMSMON_MAX_GUNZIP_BYTES", str(8 * 1024 * 1024)))
    # Sanity window for device-supplied sample timestamps (ts_ms). ts_ms drives monthly
    # partition DDL, so a broken phone clock (epoch 0, year 3000, ...) could mass-create
    # tables or crash datetime conversion. Samples outside
    # [ingest_ts_min_ms, now + ingest_ts_max_future_ms] are DROPPED (logged, counted) —
    # never rejected with a 4xx, because the phone uploader treats non-408/429 4xx as a
    # poison batch and would silently lose the valid samples alongside the bad ones.
    ingest_ts_min_ms: int = int(os.environ.get(
        "BMSMON_INGEST_TS_MIN_MS", "1577836800000"  # 2020-01-01T00:00:00Z
    ))
    ingest_ts_max_future_ms: int = int(os.environ.get(
        "BMSMON_INGEST_TS_MAX_FUTURE_MS", str(48 * 3600 * 1000)  # server now + 48h
    ))
    # GPS retention (SEC-12): samples older than this many days get their location columns
    # (lat/lon/gps_accuracy_m) set to NULL by a daily background scrub. Telemetry rows are
    # NEVER deleted — battery history is kept forever; only location data expires. Default
    # 1095 days (3 years). Set <= 0 to disable scrubbing entirely (keep GPS forever).
    gps_retention_days: int = int(os.environ.get("BMSMON_GPS_RETENTION_DAYS", "1095"))
    # In local dev (no Authentik in front), trust a synthetic identity so /web/* works.
    # Guarded: only honored when DATABASE_URL points at a local dev DB — see
    # auth.authentik.dev_trust_active().
    dev_trust_headers: bool = os.environ.get("BMSMON_DEV_TRUST_HEADERS", "0") == "1"
    dev_user: str = os.environ.get("BMSMON_DEV_USER", "dev@covert.life")
    # Groups of the synthetic dev-trust identity. Empty (default) = viewer AND admin (see
    # auth.authentik.resolve_user); set it to try the UI as e.g. a plain viewer.
    dev_groups: list[str] = field(
        default_factory=lambda: _split(os.environ.get("BMSMON_DEV_GROUPS", ""))
    )
    # SEC-19: Origins allowed to open /ws (comma/pipe separated). Browsers always send
    # Origin on a WebSocket handshake; without this check a page on any same-site
    # *.covert.life app could ride the household's Authentik cookie onto the live GPS
    # stream. Dev-trust mode additionally allows the Vite dev server and a missing Origin
    # (routers/ws.py origin_allowed). Empty = nothing allowed (fail closed).
    ws_allowed_origins: list[str] = field(
        default_factory=lambda: _split(
            os.environ.get("BMSMON_WS_ALLOWED_ORIGINS", "https://bmsmon.covert.life"))
    )
    share_owner: str = os.environ.get("BMSMON_SHARE_OWNER", "Joely")


settings = Settings()
