import os
import re
from dataclasses import dataclass, field


def _split(v: str) -> list[str]:
    return [s for s in (p.strip() for p in v.replace("|", ",").split(",")) if s]


CARTO_KEY_ENV = "BMSMON_CARTO_KEY"
_CARTO_KEY_RE = re.compile(r"[A-Za-z0-9_-]+")
_CARTO_KEY_MIN, _CARTO_KEY_MAX = 8, 128


def parse_carto_key(raw: str | None) -> tuple[str | None, str | None]:
    """BMSMON_CARTO_KEY -> (key, problem). Unset -> (None, None): having no key is a normal
    state (dev, CI), not a misconfiguration. Surrounding whitespace and quotes are stripped
    (a quoted .env value); what remains must be 8-128 of [A-Za-z0-9_-], else (None, problem).
    `problem` describes only the KIND of fault and never contains any part of the value:
    it is a credential, and `problem` goes into the log (main.py log_carto_key_status)."""
    if raw is None:
        return None, None
    v = raw.strip().strip("\"'").strip()
    if not v:
        return None, "it is set but empty"
    if not _CARTO_KEY_RE.fullmatch(v):
        return None, "it may contain only A-Z, a-z, 0-9, '_' and '-'"
    if len(v) < _CARTO_KEY_MIN:
        return None, f"it is shorter than {_CARTO_KEY_MIN} characters"
    if len(v) > _CARTO_KEY_MAX:
        return None, f"it is longer than {_CARTO_KEY_MAX} characters"
    return v, None


PHONE_LOW_PCT_ENV = "BMSMON_PHONE_LOW_PCT"
PHONE_LOW_PCT_DEFAULT = 75


def parse_phone_low_pct(raw: str | None) -> tuple[int, str | None]:
    """BMSMON_PHONE_LOW_PCT -> (percent, problem). Unset -> (75, None). Valid is one of the
    values the phone and the WebUI offer (10, 15, ... 95); anything else (empty, garbage,
    out of range, off-step) falls back to 75 with a `problem` for the one startup warning
    (main.py log_phone_low_pct_status). It is only the fallback until either side sets the
    shared threshold (phone_alert_config)."""
    if raw is None:
        return PHONE_LOW_PCT_DEFAULT, None
    try:
        v = int(raw.strip())
    except ValueError:
        return PHONE_LOW_PCT_DEFAULT, "it is not a whole number"
    if not 10 <= v <= 95 or v % 5:
        return PHONE_LOW_PCT_DEFAULT, "it is not one of 10, 15, ... 95"
    return v, None


@dataclass(frozen=True)
class Settings:
    database_url: str = os.environ.get(
        "DATABASE_URL", "postgresql://bmsmon:bmsmon@localhost:5432/bmsmon"
    )
    # SEC-13/SEC-20: bmsmon enforces Authentik group membership ITSELF, fail-closed, on
    # /web/* and /ws (auth/authentik.py authorize) instead of trusting that the Authentik
    # application binding is right; it had none until 2026-08-23. Exact, case-sensitive
    # match against X-Authentik-Groups; an empty value matches nobody.
    # Surrounding whitespace is stripped from both values, so a stray space or newline in
    # the stack .env cannot lock everyone out.
    # Viewers may read the dashboard: live + historical GPS.
    viewer_group: str = os.environ.get(
        "BMSMON_VIEWER_GROUP", "Covert.Life - Full App Access - User Group"
    ).strip()
    # Admins may mint share links / API keys / enroll codes and revoke devices. OWNER ONLY,
    # never the household access group (SEC-20). An admin also counts as a viewer.
    admin_group: str = os.environ.get(
        "BMSMON_ADMIN_GROUP", "Covert.Life - bmsmon - Admin Group"
    ).strip()
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
    # SEC-23/SRV-17: GET /api/v1/health/detail fails its "ingest" check (503 -> the uptime
    # monitor pages) once no non-revoked device has uploaded for this many seconds. The
    # phone uploads every ~15 s while monitoring; 30 min rides out a deploy or a short
    # connectivity gap and still catches a server-side ingest failure within one outing.
    deadman_ingest_s: int = int(os.environ.get("BMSMON_DEADMAN_INGEST_S", "1800"))
    # The `phone_battery` health check fails while the chair phone's last reported battery
    # level is below this percent (1..100; invalid -> 75 with a startup warning).
    phone_low_pct: int = field(
        default_factory=lambda: parse_phone_low_pct(os.environ.get(PHONE_LOW_PCT_ENV))[0])
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
    # stream. Dev-trust mode additionally allows the Vite dev server, a missing Origin and
    # a same-origin page (routers/ws.py origin_allowed). Empty = nothing allowed (fail
    # closed).
    ws_allowed_origins: list[str] = field(
        default_factory=lambda: _split(
            os.environ.get("BMSMON_WS_ALLOWED_ORIGINS", "https://bmsmon.covert.life"))
    )
    share_owner: str = os.environ.get("BMSMON_SHARE_OWNER", "Joely")
    # CARTO basemap key for the Journey map and the guest share page; without one CARTO
    # serves "API KEY REQUIRED" placeholder tiles. The repo and the GHCR image are public,
    # so it is RUNTIME-ONLY: set on the NAS, served to viewers by GET /web/map-config and
    # to active share links by GET /share/{token}/map-config, never committed and never
    # baked into a build. Unset logs one startup INFO line; an invalid value becomes None
    # with one startup WARNING that never includes it; repr=False keeps it out of any
    # repr(settings).
    carto_key: str | None = field(
        default_factory=lambda: parse_carto_key(os.environ.get(CARTO_KEY_ENV))[0],
        repr=False)


settings = Settings()
