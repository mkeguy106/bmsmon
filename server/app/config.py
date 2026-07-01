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
    # In local dev (no Authentik in front), trust a synthetic identity so /web/* works.
    dev_trust_headers: bool = os.environ.get("BMSMON_DEV_TRUST_HEADERS", "0") == "1"
    dev_user: str = os.environ.get("BMSMON_DEV_USER", "dev@covert.life")
    dev_groups: list[str] = field(
        default_factory=lambda: _split(
            os.environ.get("BMSMON_DEV_GROUPS", "Covert.life - Full App Access - User Group")
        )
    )


settings = Settings()
