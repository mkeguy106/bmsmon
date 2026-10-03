"""Authentik test identities, built from the LIVE settings (BMSMON_VIEWER_GROUP /
BMSMON_ADMIN_GROUP), so no test ever hardcodes a group spelling again. The pre-2026-10
suite hardcoded the old lowercase spelling in 17 files."""
from app.config import settings

VIEWER_GROUP = settings.viewer_group
ADMIN_GROUP = settings.admin_group


def identity(username: str, *groups: str) -> dict[str, str]:
    """Headers as Authentik's forward-auth outpost sends them: groups pipe-separated."""
    h = {"X-authentik-username": username}
    if groups:
        h["X-authentik-groups"] = "|".join(groups)
    return h


VIEWER_H = identity("viewer", VIEWER_GROUP)         # household member: reads, no admin
ADMIN_H = identity("joel", ADMIN_GROUP)             # owner: admin (implies viewer)
OUTSIDER_H = identity("rando", "Some Other Group")  # authenticated, not a member
