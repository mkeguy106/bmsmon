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
    # In local dev (no Authentik in front), trust a synthetic identity so /web/* works.
    dev_trust_headers: bool = os.environ.get("BMSMON_DEV_TRUST_HEADERS", "0") == "1"
    dev_user: str = os.environ.get("BMSMON_DEV_USER", "dev@covert.life")
    dev_groups: list[str] = field(
        default_factory=lambda: _split(
            os.environ.get("BMSMON_DEV_GROUPS", "Covert.life - Full App Access - User Group")
        )
    )


settings = Settings()
