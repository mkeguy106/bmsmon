import logging
import secrets
from dataclasses import dataclass
from urllib.parse import urlparse

from fastapi import HTTPException, Request
from starlette.datastructures import Headers

from app.config import settings

logger = logging.getLogger(__name__)


@dataclass
class AuthUser:
    username: str
    groups: list[str]


def _split_groups(v: str) -> list[str]:
    return [g.strip() for g in v.replace("|", ",").split(",") if g.strip()]


# DB hosts that identify a local dev environment (see dev_trust_active). "db" is the
# compose-internal service name used by throwaway all-in-one dev stacks; prod points
# at the bmsmon-db container by its qualified stack name, not bare "db".
_DEV_DB_HOSTS = {"localhost", "127.0.0.1", "::1", "db"}

_dev_trust_refused_logged = False


def dev_trust_active() -> bool:
    """SRV-8/SEC-6 guard: BMSMON_DEV_TRUST_HEADERS grants a synthetic admin identity
    to EVERY request, so it must never be active against a real deployment. Only honor
    it when DATABASE_URL points at a local dev database (localhost/127.0.0.1/::1/"db");
    otherwise log a loud warning once and behave as if the flag were unset."""
    global _dev_trust_refused_logged
    if not settings.dev_trust_headers:
        return False
    host = urlparse(settings.database_url).hostname or ""
    if host in _DEV_DB_HOSTS:
        return True
    if not _dev_trust_refused_logged:
        logger.warning(
            "BMSMON_DEV_TRUST_HEADERS=1 REFUSED: DATABASE_URL host %r is not a local dev "
            "database (%s). Dev-trust would grant synthetic admin to every request — "
            "treating it as unset.", host, "/".join(sorted(_DEV_DB_HOSTS)))
        _dev_trust_refused_logged = True
    return False


def proxy_secret_ok(headers: Headers) -> bool:
    """When BMSMON_PROXY_SECRET is set, the reverse proxy must inject a matching
    X-Bmsmon-Proxy-Secret header; otherwise the X-Authentik-* identity headers are
    not trusted at all (defense in depth against direct-to-container requests).
    Unset (default) = check disabled."""
    if not settings.proxy_secret:
        return True
    supplied = headers.get("x-bmsmon-proxy-secret") or ""
    return secrets.compare_digest(supplied.encode(), settings.proxy_secret.encode())


def resolve_user(headers: Headers) -> "AuthUser | None":
    """Identity resolution shared by HTTP /web/* and the /ws WebSocket handshake.

    Checks the proxy shared secret BEFORE trusting any X-Authentik-* header (or the
    dev-trust path). Returns None when the request carries no trustworthy identity.
    """
    if not proxy_secret_ok(headers):
        return None
    username = headers.get("x-authentik-username")
    if username:
        return AuthUser(username, _split_groups(headers.get("x-authentik-groups", "")))
    if dev_trust_active():
        return AuthUser(settings.dev_user, list(settings.dev_groups))
    return None


def current_user(request: Request) -> AuthUser:
    user = resolve_user(request.headers)
    if user is None:
        raise HTTPException(401, "not authenticated")
    return user


def require_admin(request: Request) -> AuthUser:
    user = current_user(request)
    if settings.admin_group not in user.groups:
        raise HTTPException(403, "admin group required")
    return user
