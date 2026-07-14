import os
from dataclasses import dataclass, field


def _split(v: str) -> list[str]:
    return [s for s in (p.strip() for p in v.replace("|", ",").split(",")) if s]


@dataclass(frozen=True)
class Settings:
    database_url: str = os.environ.get(
        "DATABASE_URL", "postgresql://bmsmon:bmsmon@localhost:5432/bmsmon"
    )
    admin_group: str = os.environ.get(
        "BMSMON_ADMIN_GROUP", "Covert.life - Full App Access - User Group"
    )
    # Optional shared secret between the reverse proxy (Traefik) and the app. When set, every
    # /web/* request and the /ws handshake must carry an X-Bmsmon-Proxy-Secret header exactly
    # matching this value BEFORE any X-Authentik-* identity header is trusted — defense in depth
    # against anything that can reach the container directly and forge identity headers.
    # Empty (default) = feature off. To enable in prod: set BMSMON_PROXY_SECRET in the stack .env
    # AND configure Traefik to inject the header (middleware customRequestHeaders) on the
    # Authentik-routed router. /api/v1/* is unaffected (device-JWT auth).
    proxy_secret: str = os.environ.get("BMSMON_PROXY_SECRET", "")
    # Request-body caps for the device endpoints (/api/v1/ingest, /api/v1/config): max bytes read
    # off the wire, and max decompressed size when the body is gzipped (anti gzip-bomb).
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
    dev_groups: list[str] = field(
        default_factory=lambda: _split(
            os.environ.get("BMSMON_DEV_GROUPS", "Covert.life - Full App Access - User Group")
        )
    )
    share_owner: str = os.environ.get("BMSMON_SHARE_OWNER", "Joely")


settings = Settings()
