import secrets
from dataclasses import dataclass

from fastapi import HTTPException, Request
from starlette.datastructures import Headers

from app.config import settings


@dataclass
class AuthUser:
    username: str
    groups: list[str]


def _split_groups(v: str) -> list[str]:
    return [g.strip() for g in v.replace("|", ",").split(",") if g.strip()]


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
    if settings.dev_trust_headers:
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
