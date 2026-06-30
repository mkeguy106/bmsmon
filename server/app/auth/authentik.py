from dataclasses import dataclass

from fastapi import HTTPException, Request

from app.config import settings


@dataclass
class AuthUser:
    username: str
    groups: list[str]


def _split_groups(v: str) -> list[str]:
    return [g.strip() for g in v.replace("|", ",").split(",") if g.strip()]


def current_user(request: Request) -> AuthUser:
    username = request.headers.get("x-authentik-username")
    if username:
        return AuthUser(username, _split_groups(request.headers.get("x-authentik-groups", "")))
    if settings.dev_trust_headers:
        return AuthUser(settings.dev_user, list(settings.dev_groups))
    raise HTTPException(401, "not authenticated")


def require_admin(request: Request) -> AuthUser:
    user = current_user(request)
    if settings.admin_group not in user.groups:
        raise HTTPException(403, "admin group required")
    return user
