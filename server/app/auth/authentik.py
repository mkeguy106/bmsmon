import logging
import secrets
from dataclasses import dataclass
from urllib.parse import urlparse

from fastapi import HTTPException, Request
from starlette.datastructures import Headers

from app.caching import TouchThrottle
from app.config import settings

logger = logging.getLogger(__name__)

# Denied-access audit trail (SEC-13): one WARNING per user per interval, so a stray
# account reloading the dashboard can't flood the 5 MB prod log. Process-local.
_DENY_LOG_INTERVAL_S = 300.0
_deny_log = TouchThrottle(interval_s=_DENY_LOG_INTERVAL_S)


@dataclass
class AuthUser:
    username: str
    groups: list[str]


def _split_groups(v: str) -> list[str]:
    """X-Authentik-Groups as the outpost sends it: names joined with "|". Split on "|"
    ONLY: a group name may itself contain a comma, and splitting on it would turn
    "Friends, <admin group>" into membership of the admin group."""
    return [g.strip() for g in v.split("|") if g.strip()]


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
    """Identity only: WHO is calling. Never grants access by itself. Every /web/* route
    and the /ws handshake go through authorize(), which adds the group gate.

    Checks the proxy shared secret BEFORE trusting any X-Authentik-* header (or the
    dev-trust path). Returns None when the request carries no trustworthy identity.
    """
    if not proxy_secret_ok(headers):
        return None
    username = headers.get("x-authentik-username")
    if username:
        return AuthUser(username, _split_groups(headers.get("x-authentik-groups", "")))
    if dev_trust_active():
        # The local synthetic identity is a viewer AND an admin unless BMSMON_DEV_GROUPS
        # overrides it (e.g. to try the dashboard as a plain viewer).
        groups = list(settings.dev_groups) or [settings.viewer_group, settings.admin_group]
        return AuthUser(settings.dev_user, groups)
    return None


def is_admin(user: AuthUser) -> bool:
    """Exact, case-sensitive membership; an empty setting matches nobody (fail closed)."""
    return bool(settings.admin_group) and settings.admin_group in user.groups


def is_viewer(user: AuthUser) -> bool:
    """Viewer-group member, or admin: an admin also counts as a viewer."""
    return ((bool(settings.viewer_group) and settings.viewer_group in user.groups)
            or is_admin(user))


def authorize(headers: Headers, *, admin: bool = False) -> AuthUser:
    """THE access gate for /web/* and /ws (SEC-13/SEC-20), FAIL-CLOSED.

    401 when there is no trustworthy identity, 403 when the identity is not a viewer
    (or not an admin, for admin routes). /ws maps these to close codes 4401/4403.
    """
    user = resolve_user(headers)
    if user is None:
        raise HTTPException(401, "not authenticated")
    if not is_viewer(user):
        if _deny_log.should_touch(user.username):
            logger.warning("access denied: %r is not in the viewer group", user.username)
        raise HTTPException(403, "viewer group required")
    if admin and not is_admin(user):
        raise HTTPException(403, "admin group required")
    return user


def current_user(request: Request) -> AuthUser:
    """FastAPI dependency for every /web/* reader: an authorized viewer."""
    return authorize(request.headers)


def require_admin(request: Request) -> AuthUser:
    """FastAPI dependency for admin-only /web/* routes."""
    return authorize(request.headers, admin=True)
