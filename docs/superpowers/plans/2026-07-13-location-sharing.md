# Live Location Sharing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Time-limited, named, revocable public share links (`/share/<token>`) that bypass Authentik and show a guest a live "find me" map of today's chair location only — plus a share dialog in Journey, a management panel in Settings, and a direction-to-target arrow for guests.

**Architecture:** A new unauthenticated FastAPI router (`/share/{token}` page + `/share/{token}/feed` JSON) validated per-request against a `location_shares` table storing only sha256(token); admin CRUD rides the existing Authentik-gated `/web` router. The guest page is a separate Vite build with `base: "/share/"` so all its assets stay inside the new no-Authentik Traefik zone. Spec: `docs/superpowers/specs/2026-07-13-location-sharing-design.md`.

**Tech Stack:** FastAPI + asyncpg + Postgres 16 (server), React + TypeScript + Leaflet + Vite (web), Traefik labels (infra, `~/qnap-nas-docker`).

## Global Constraints

- Commit messages must NEVER reference AI, Claude, or automated generation (repo rule).
- Token: `secrets.token_urlsafe(24)`; DB stores only `hash_code(token)` (sha256 hexdigest from `app/auth/enroll.py`); the full link leaves the server exactly once, at creation.
- Unknown and revoked tokens → identical bare 404 on both page and feed. Expired → friendly HTML page / HTTP 410 on feed.
- Guest feed fields are exactly `t`/`lat`/`lon` per point — never any battery field.
- Day clamp is server-side: guests send no time parameters.
- All `/share/` responses carry `Referrer-Policy: no-referrer` and `Cache-Control: no-store`.
- `web/share/index.html` MUST carry the viewport meta (v2 mobile lesson — without it phones render a 980px virtual viewport).
- Server tests: `docker compose -f server/docker-compose.dev.yml up -d` once, then `cd server && .venv/bin/python -m pytest` (bare `python` lacks deps).
- Web tests/build: `cd web && npm test` / `npm run build`.

---

### Task 1: `location_shares` schema, queries, and admin CRUD (`/web/shares`)

**Files:**
- Modify: `server/app/db/schema.sql` (append at end)
- Modify: `server/app/db/queries.py` (append at end)
- Modify: `server/app/models.py` (append at end)
- Modify: `server/app/routers/web.py` (add three endpoints + imports)
- Modify: `server/tests/conftest.py` (TRUNCATE list)
- Test: `server/tests/test_web_shares.py` (new)

**Interfaces:**
- Consumes: `hash_code(code: str) -> str` from `app.auth.enroll`; `require_admin`, `jsonable`, `get_pool` (existing).
- Produces (Task 2 relies on these exact signatures):
  - `q.create_location_share(conn, token_hash: str, name: str, created_by: str, created_at_ms: int, expires_at_ms: int) -> int`
  - `q.get_location_share(conn, token_hash: str) -> dict | None` (keys: `id,name,created_at,expires_at,revoked_at`)
  - `q.touch_location_share(conn, share_id: int, now_ms: int) -> None`
  - `q.list_location_shares(conn, now_ms: int, keep_ended_ms: int) -> list[dict]`
  - `q.revoke_location_share(conn, share_id: int, now_ms: int) -> None`
  - `POST /web/shares` body `{name, duration}` → `{id, name, expires_at, path}` where `path = "/share/<token>"`.

- [ ] **Step 1: Write the failing tests**

Create `server/tests/test_web_shares.py`:

```python
import time

from app.auth.enroll import hash_code
from app.db import queries as q

ADMIN = {"X-authentik-username": "joel",
         "X-authentik-groups": "Covert.life - Full App Access - User Group"}
NON_ADMIN = {"X-authentik-username": "rando", "X-authentik-groups": "Some Other Group"}


async def test_shares_require_admin(client):
    assert (await client.get("/web/shares", headers=NON_ADMIN)).status_code == 403
    assert (await client.post("/web/shares", headers=NON_ADMIN,
                              json={"name": "Dave", "duration": "1h"})).status_code == 403
    assert (await client.delete("/web/shares/1", headers=NON_ADMIN)).status_code == 403


async def test_create_share_returns_path_once(client):
    r = await client.post("/web/shares", headers=ADMIN,
                          json={"name": "Dave", "duration": "1h"})
    assert r.status_code == 200
    body = r.json()
    assert body["name"] == "Dave"
    assert body["path"].startswith("/share/")
    token = body["path"].removeprefix("/share/")
    assert len(token) >= 32  # token_urlsafe(24) -> 32 chars
    now_ms = int(time.time() * 1000)
    assert 0 < body["expires_at"] - now_ms <= 3_600_000
    # the listing never exposes the token or its hash
    listing = (await client.get("/web/shares", headers=ADMIN)).json()["shares"]
    assert len(listing) == 1
    assert "token_hash" not in listing[0]
    assert "path" not in listing[0]
    assert listing[0]["access_count"] == 0


async def test_share_body_validation(client):
    r = await client.post("/web/shares", headers=ADMIN,
                          json={"name": "Dave", "duration": "2h"})
    assert r.status_code == 422
    r = await client.post("/web/shares", headers=ADMIN,
                          json={"name": "   ", "duration": "1h"})
    assert r.status_code == 422


async def test_revoke_sets_revoked_at(client):
    r = await client.post("/web/shares", headers=ADMIN,
                          json={"name": "Dave", "duration": "1d"})
    sid = r.json()["id"]
    assert (await client.delete(f"/web/shares/{sid}", headers=ADMIN)).status_code == 200
    listing = (await client.get("/web/shares", headers=ADMIN)).json()["shares"]
    assert listing[0]["revoked_at"] is not None


async def test_listing_drops_long_ended_shares(app, client):
    now_ms = int(time.time() * 1000)
    day = 86_400_000
    pool = app.state.pool
    async with pool.acquire() as conn:
        await q.create_location_share(conn, hash_code("old"), "Old", "joel",
                                      now_ms - 9 * day, now_ms - 8 * day)
        await q.create_location_share(conn, hash_code("recent"), "Recent", "joel",
                                      now_ms - 2 * day, now_ms - day)
        await q.create_location_share(conn, hash_code("live"), "Live", "joel",
                                      now_ms, now_ms + 3_600_000)
    names = [s["name"] for s in
             (await client.get("/web/shares", headers=ADMIN)).json()["shares"]]
    assert names == ["Live", "Recent"]
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd server && .venv/bin/python -m pytest tests/test_web_shares.py -v`
Expected: FAIL — 404s (routes missing) and `AttributeError` on `q.create_location_share`. (If Postgres isn't up: `docker compose -f server/docker-compose.dev.yml up -d` first.)

- [ ] **Step 3: Schema**

Append to `server/app/db/schema.sql`:

```sql
-- Time-limited public location-share links (capability URLs; the /share/ Traefik zone
-- bypasses Authentik). Only sha256(token) is stored — the full URL is returned once at
-- creation and never again. Guests get today's GPS trail only; feed queries clamp
-- server-side and never return battery fields. revoked_at NULL = not revoked.
CREATE TABLE IF NOT EXISTS location_shares (
  id bigserial PRIMARY KEY,
  token_hash text NOT NULL UNIQUE,
  name text NOT NULL,
  created_at bigint NOT NULL,
  expires_at bigint NOT NULL,
  revoked_at bigint,
  created_by text,
  last_access_ms bigint,
  access_count bigint NOT NULL DEFAULT 0
);
```

- [ ] **Step 4: Queries**

Append to `server/app/db/queries.py`:

```python
async def create_location_share(conn, token_hash: str, name: str, created_by: str,
                                created_at_ms: int, expires_at_ms: int) -> int:
    row = await conn.fetchrow(
        """INSERT INTO location_shares (token_hash, name, created_by, created_at, expires_at)
           VALUES ($1, $2, $3, $4, $5) RETURNING id""",
        token_hash, name, created_by, created_at_ms, expires_at_ms)
    return int(row["id"])


async def get_location_share(conn, token_hash: str) -> dict | None:
    row = await conn.fetchrow(
        """SELECT id, name, created_at, expires_at, revoked_at
             FROM location_shares WHERE token_hash = $1""", token_hash)
    return dict(row) if row else None


async def touch_location_share(conn, share_id: int, now_ms: int) -> None:
    await conn.execute(
        """UPDATE location_shares
              SET last_access_ms = $2, access_count = access_count + 1
            WHERE id = $1""", share_id, now_ms)


async def list_location_shares(conn, now_ms: int, keep_ended_ms: int) -> list[dict]:
    """Active shares plus anything that ended (revoked or expired) within keep_ended_ms.
    end-of-life = revoked_at when revoked, else expires_at — COALESCE covers both."""
    rows = await conn.fetch(
        """SELECT id, name, created_at, expires_at, revoked_at, last_access_ms, access_count
             FROM location_shares
            WHERE COALESCE(revoked_at, expires_at) > $1 - $2
            ORDER BY created_at DESC""", now_ms, keep_ended_ms)
    return [dict(r) for r in rows]


async def revoke_location_share(conn, share_id: int, now_ms: int) -> None:
    await conn.execute(
        "UPDATE location_shares SET revoked_at = $2 WHERE id = $1 AND revoked_at IS NULL",
        share_id, now_ms)
```

- [ ] **Step 5: Models**

Append to `server/app/models.py`:

```python
class ShareCreateBody(BaseModel):
    name: str
    duration: str  # "1h" | "1d" | "1w"

    @field_validator("name")
    @classmethod
    def _name(cls, v: str) -> str:
        v = v.strip()
        if not v or len(v) > 80:
            raise ValueError("invalid name")
        return v

    @field_validator("duration")
    @classmethod
    def _duration(cls, v: str) -> str:
        if v not in ("1h", "1d", "1w"):
            raise ValueError("duration must be 1h, 1d or 1w")
        return v


class ShareCreateResponse(BaseModel):
    id: int
    name: str
    expires_at: int
    path: str  # "/share/<token>" — the client prepends window.location.origin
```

- [ ] **Step 6: Endpoints**

In `server/app/routers/web.py`: add `import secrets` to the imports, extend the models import line to `from app.models import MintCodeResponse, NoteBody, OkResponse, ShareCreateBody, ShareCreateResponse`, and append:

```python
_SHARE_DURATION_MS = {"1h": 3_600_000, "1d": 86_400_000, "1w": 7 * 86_400_000}
SHARE_LIST_KEEP_MS = 7 * 86_400_000  # ended shares stay listed for 7 days


@router.post("/shares", response_model=ShareCreateResponse)
async def create_share(body: ShareCreateBody, user: AuthUser = Depends(require_admin),
                       pool=Depends(get_pool)):
    """Mint a public location-share link. Admin-gated: a share grants unauthenticated
    access, same trust class as an enroll code. The token leaves the server only here."""
    token = secrets.token_urlsafe(24)
    now_ms = int(time.time() * 1000)
    expires_at = now_ms + _SHARE_DURATION_MS[body.duration]
    async with pool.acquire() as conn:
        share_id = await q.create_location_share(
            conn, hash_code(token), body.name, user.username, now_ms, expires_at)
    return ShareCreateResponse(id=share_id, name=body.name, expires_at=expires_at,
                               path=f"/share/{token}")


@router.get("/shares")
async def list_shares(user: AuthUser = Depends(require_admin), pool=Depends(get_pool)):
    now_ms = int(time.time() * 1000)
    async with pool.acquire() as conn:
        return {"shares": jsonable(
            await q.list_location_shares(conn, now_ms, SHARE_LIST_KEEP_MS))}


@router.delete("/shares/{share_id}")
async def revoke_share(share_id: int, user: AuthUser = Depends(require_admin),
                       pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        await q.revoke_location_share(conn, share_id, int(time.time() * 1000))
    return {"revoked": share_id}
```

- [ ] **Step 7: conftest TRUNCATE**

In `server/tests/conftest.py`, extend the TRUNCATE statement to include the new table:

```python
            await conn.execute(
                "TRUNCATE samples, batteries, enrollment_codes, devices, device_temp_config, "
                "device_alert_config, device_range_config, web_notes, location_shares "
                "RESTART IDENTITY CASCADE"
            )
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `cd server && .venv/bin/python -m pytest tests/test_web_shares.py -v`
Expected: 5 passed. Then the full suite: `.venv/bin/python -m pytest` — all pass.

- [ ] **Step 9: Commit**

```bash
git add server/app/db/schema.sql server/app/db/queries.py server/app/models.py \
        server/app/routers/web.py server/tests/conftest.py server/tests/test_web_shares.py
git commit -m "feat(server): location-share admin CRUD (/web/shares) + schema"
```

---

### Task 2: Public `/share` router (guest page + feed)

**Files:**
- Create: `server/app/routers/share.py`
- Modify: `server/app/main.py` (import + register router, share limiter)
- Modify: `server/app/config.py` (add `share_owner`)
- Modify: `server/app/db/queries.py` (append `gps_track_all`)
- Test: `server/tests/test_share_public.py` (new)

**Interfaces:**
- Consumes: Task 1's `q.create_location_share` / `q.get_location_share` / `q.touch_location_share` / `q.revoke_location_share`; `hash_code`; `RateLimiter`, `client_key` from `app.ratelimit`; `q.sample_row` / `q.insert_samples` (existing, for tests).
- Produces (the guest page in Task 6 relies on this):
  - `GET /share/{token}` → HTML (active: `dist/share/index.html`; expired: friendly page; gone: 404)
  - `GET /share/{token}/feed` → `{"points": [{"t","lat","lon"}...], "last": {...}|null, "expires_at": int, "now": int, "owner": str}` (404 gone / 410 expired)
  - `share_status(share: dict | None, now_ms: int) -> str` and `day_window_ms(now: datetime) -> tuple[int, int]` (pure, exported for tests)

- [ ] **Step 1: Write the failing tests**

Create `server/tests/test_share_public.py`:

```python
import time
from datetime import datetime, timezone

from httpx import ASGITransport, AsyncClient

from app.auth.enroll import hash_code
from app.db import queries as q
from app.routers.share import day_window_ms, share_status

DEV = "00000000-0000-0000-0000-000000000002"
A = "C8:47:80:15:67:44"


def test_share_status():
    now = 1_000_000
    assert share_status(None, now) == "gone"
    assert share_status({"revoked_at": 5, "expires_at": now + 1}, now) == "gone"
    assert share_status({"revoked_at": None, "expires_at": now + 1}, now) == "active"
    assert share_status({"revoked_at": None, "expires_at": now}, now) == "expired"


def test_day_window_is_local_midnight_to_now():
    now = datetime(2026, 7, 13, 15, 30, tzinfo=timezone.utc)
    from_ms, to_ms = day_window_ms(now)
    assert to_ms == int(now.timestamp() * 1000)
    start = datetime.fromtimestamp(from_ms / 1000).astimezone()
    assert (start.hour, start.minute, start.second) == (0, 0, 0)
    assert 0 < to_ms - from_ms <= 25 * 3600 * 1000  # <= a DST-long day


async def _mk_share(conn, token: str, created_ms: int, expires_ms: int,
                    revoked_ms: int | None = None) -> int:
    sid = await q.create_location_share(conn, hash_code(token), "T", "joel",
                                        created_ms, expires_ms)
    if revoked_ms is not None:
        await q.revoke_location_share(conn, sid, revoked_ms)
    return sid


async def _seed_fix(conn, ts_ms: int, lat: float, lon: float):
    rows = [q.sample_row(DEV, A, {"ts_ms": ts_ms, "lat": lat, "lon": lon,
                                  "power_w": -60.0, "current_a": -4.0, "soc": 88})]
    assert await q.insert_samples(conn, rows) == 1


async def _seed_device(conn):
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-share-public-1", b"\x00")


async def test_unknown_and_revoked_are_identical_404(app, client):
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _mk_share(conn, "tok-revoked", now_ms, now_ms + 3_600_000, revoked_ms=now_ms)
    for path in ("/share/{t}", "/share/{t}/feed"):
        unknown = await client.get(path.format(t="tok-unknown"))
        revoked = await client.get(path.format(t="tok-revoked"))
        assert unknown.status_code == revoked.status_code == 404
        assert unknown.content == revoked.content


async def test_expired_page_and_feed(app, client):
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _mk_share(conn, "tok-exp", now_ms - 7_200_000, now_ms - 3_600_000)
    page = await client.get("/share/tok-exp")
    assert page.status_code == 200
    assert b"expired" in page.content.lower()
    assert (await client.get("/share/tok-exp/feed")).status_code == 410


async def test_feed_today_only_no_battery_fields(app, client):
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _seed_device(conn)
        await _mk_share(conn, "tok-live", now_ms, now_ms + 3_600_000)
        await _seed_fix(conn, now_ms - 60_000, 43.0, -87.9)          # today
        await _seed_fix(conn, now_ms - 2 * 86_400_000, 44.0, -88.9)  # 2 days ago: clamped out
    r = await client.get("/share/tok-live/feed")
    assert r.status_code == 200
    assert r.headers["cache-control"] == "no-store"
    assert r.headers["referrer-policy"] == "no-referrer"
    body = r.json()
    assert set(body.keys()) == {"points", "last", "expires_at", "now", "owner"}
    assert len(body["points"]) == 1
    assert set(body["points"][0].keys()) == {"t", "lat", "lon"}
    assert body["last"]["lat"] == 43.0
    # access tracking
    listing = None
    async with app.state.pool.acquire() as conn:
        listing = await q.list_location_shares(conn, now_ms, 86_400_000)
    assert listing[0]["access_count"] == 1
    assert listing[0]["last_access_ms"] is not None


async def test_feed_rate_limited_per_ip(app):
    transport = ASGITransport(app=app, client=("9.9.9.9", 12345))
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        codes = [(await c.get("/share/tok-nope/feed")).status_code for _ in range(61)]
    assert codes[0] == 404
    assert codes[-1] == 429


async def test_active_page_serves_guest_shell(app, client, tmp_path, monkeypatch):
    (tmp_path / "share").mkdir()
    (tmp_path / "share" / "index.html").write_text("<html>guest-shell</html>")
    monkeypatch.setenv("BMSMON_WEB_DIST", str(tmp_path))
    now_ms = int(time.time() * 1000)
    async with app.state.pool.acquire() as conn:
        await _mk_share(conn, "tok-page", now_ms, now_ms + 3_600_000)
    r = await client.get("/share/tok-page")
    assert r.status_code == 200
    assert b"guest-shell" in r.content
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd server && .venv/bin/python -m pytest tests/test_share_public.py -v`
Expected: FAIL — `ImportError` (no `app.routers.share`).

- [ ] **Step 3: Fleet-wide GPS query**

Append to `server/app/db/queries.py`:

```python
async def gps_track_all(conn, from_ms: int, to_ms: int) -> list[dict]:
    """15-second buckets of GPS fixes across the whole fleet — coordinates only.
    Feeds the public location-share guest page: deliberately no battery columns."""
    rows = await conn.fetch(
        """SELECT (ts_ms / 15000) * 15000 AS bucket_ms,
                  avg(lat)::double precision AS lat, avg(lon)::double precision AS lon
             FROM samples
            WHERE ts_ms >= $1 AND ts_ms < $2
              AND link_event IS NULL AND lat IS NOT NULL AND lon IS NOT NULL
            GROUP BY bucket_ms ORDER BY bucket_ms""",
        from_ms, to_ms,
    )
    return [dict(r) for r in rows]
```

- [ ] **Step 4: Config**

In `server/app/config.py`, add to the `Settings` dataclass (next to the other simple string fields):

```python
    share_owner: str = os.environ.get("BMSMON_SHARE_OWNER", "Joely")
```

- [ ] **Step 5: The router**

Create `server/app/routers/share.py`:

```python
"""Public location-share endpoints. The /share/ Traefik zone bypasses Authentik, so
these are reachable by anyone holding a share URL (capability link). Security model:
192-bit tokens (secrets.token_urlsafe(24)), only sha256 stored, unknown/revoked are an
indistinguishable bare 404, expired gets a human page, every response is
no-store/no-referrer, and a per-IP rate limiter throttles scanning. Guests only ever
see today's GPS trail — never battery fields (see queries.gps_track_all)."""

import os
import time
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse

from app.auth.enroll import hash_code
from app.config import settings
from app.db import queries as q
from app.db.pool import get_pool
from app.ratelimit import client_key

router = APIRouter(prefix="/share")

_SEC_HEADERS = {"Referrer-Policy": "no-referrer", "Cache-Control": "no-store"}

_EXPIRED_HTML = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Share expired</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#09090b;color:#f4f4f5;font-family:Inter,system-ui,sans-serif;text-align:center;
padding:24px}p{color:#a1a1aa}</style></head>
<body><div><h2>This location share has expired</h2>
<p>Ask for a new link to keep following along.</p></div></body></html>"""


def share_status(share: dict | None, now_ms: int) -> str:
    """'active' | 'expired' | 'gone'. Unknown and revoked are both 'gone' — deliberately
    indistinguishable to probers; only a genuinely expired (once-valid) link admits it."""
    if share is None or share.get("revoked_at") is not None:
        return "gone"
    return "active" if now_ms < share["expires_at"] else "expired"


def day_window_ms(now: datetime) -> tuple[int, int]:
    """[local midnight, now] in epoch ms — the guest window is always just today,
    regardless of share duration (server-side clamp; guests send no time params).
    Local = the container's TZ env, i.e. the household timezone."""
    local = now.astimezone()
    start = local.replace(hour=0, minute=0, second=0, microsecond=0)
    return int(start.timestamp() * 1000), int(local.timestamp() * 1000)


async def _resolve(request: Request, token: str, pool) -> tuple[str, dict | None]:
    key = client_key(request.client.host if request.client else None, request.headers)
    if not request.app.state.share_limiter.allow(key):
        raise HTTPException(429, "too many requests")
    async with pool.acquire() as conn:
        share = await q.get_location_share(conn, hash_code(token))
    return share_status(share, int(time.time() * 1000)), share


@router.get("/{token}")
async def share_page(token: str, request: Request, pool=Depends(get_pool)):
    status, _share = await _resolve(request, token, pool)
    if status == "gone":
        raise HTTPException(404, "Not Found")
    if status == "expired":
        return HTMLResponse(_EXPIRED_HTML, headers=_SEC_HEADERS)
    index = os.path.join(os.environ.get("BMSMON_WEB_DIST", "/app/web/dist"),
                         "share", "index.html")
    return FileResponse(index, headers=_SEC_HEADERS)


@router.get("/{token}/feed")
async def share_feed(token: str, request: Request, pool=Depends(get_pool)):
    status, share = await _resolve(request, token, pool)
    if status == "gone":
        raise HTTPException(404, "Not Found")
    if status == "expired":
        raise HTTPException(410, "share expired")
    from_ms, now_ms = day_window_ms(datetime.now(timezone.utc))
    async with pool.acquire() as conn:
        rows = await q.gps_track_all(conn, from_ms, now_ms + 1)
        await q.touch_location_share(conn, share["id"], now_ms)
    points = [{"t": int(r["bucket_ms"]), "lat": r["lat"], "lon": r["lon"]} for r in rows]
    return JSONResponse(
        {"points": points, "last": points[-1] if points else None,
         "expires_at": share["expires_at"], "now": now_ms, "owner": settings.share_owner},
        headers=_SEC_HEADERS)
```

- [ ] **Step 6: Register router + limiter**

In `server/app/main.py`: change the routers import to `from app.routers import api_device, share, web, ws`, and inside `create_app()` add (next to the enroll limiter, before the StaticFiles mount):

```python
    # Per-IP limiter for the public /share zone: a guest page polls ~6-8/min, so
    # 60/min is invisible to legitimate use and throttles token scanning.
    app.state.share_limiter = RateLimiter(max_attempts=60, window_s=60)
    app.include_router(share.router)
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd server && .venv/bin/python -m pytest tests/test_share_public.py -v`
Expected: 7 passed. Then full suite: `.venv/bin/python -m pytest` — all pass.

- [ ] **Step 8: Commit**

```bash
git add server/app/routers/share.py server/app/main.py server/app/config.py \
        server/app/db/queries.py server/tests/test_share_public.py
git commit -m "feat(server): public /share router — guest page + today-only GPS feed"
```

---

### Task 3: Guest-page build entry (Vite `base: /share/`)

**Files:**
- Create: `web/vite.config.share.ts`
- Create: `web/share/index.html`
- Create: `web/share/src/main.tsx`
- Create: `web/share/src/App.tsx` (placeholder; replaced in Task 6)
- Modify: `web/vite.config.ts` (no change to inputs — separate config; only verify)
- Modify: `web/tsconfig.json` (include `share`)
- Modify: `web/package.json` (build script)

**Interfaces:**
- Produces: `npm run build` emits `web/dist/share/index.html` whose asset URLs all start with `/share/assets/` — required because `/assets/` is behind Authentik. The Dockerfile already copies all of `web/dist`, so no Docker change is needed.

- [ ] **Step 1: Share-page Vite config**

Create `web/vite.config.share.ts`:

```ts
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The public share page is its own build with base "/share/" so every asset URL stays
// inside the unauthenticated /share/ Traefik zone — the main bundle's /assets/ sit
// behind Authentik and would 302 an anonymous guest to SSO.
export default defineConfig({
  plugins: [react()],
  root: "share",
  base: "/share/",
  build: { outDir: "../dist/share", emptyOutDir: true },
});
```

- [ ] **Step 2: Entry HTML**

Create `web/share/index.html`:

```html
<!doctype html>
<html lang="en">
  <head><meta charset="utf-8" /><title>Live location</title>
    <!-- REQUIRED (v2 mobile lesson): without this, phones lay out a virtual 980px
         viewport scaled to ~40% — microscopic text. -->
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
    <meta name="color-scheme" content="light dark" />
    <meta name="darkreader-lock" />
    <meta name="robots" content="noindex, nofollow" />
    <script>
      try {
        document.documentElement.dataset.theme =
          matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
      } catch (e) { document.documentElement.dataset.theme = "dark"; }
    </script>
    <link rel="preconnect" href="https://fonts.googleapis.com" />
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600;700&display=swap" rel="stylesheet" />
  </head>
  <body><div id="root"></div><script type="module" src="./src/main.tsx"></script></body>
</html>
```

- [ ] **Step 3: Bootstrap + placeholder App**

Create `web/share/src/main.tsx`:

```tsx
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "../../src/v2/tokens.css";

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
```

Create `web/share/src/App.tsx` (placeholder — Task 6 replaces it):

```tsx
export default function App() {
  return <div style={{ padding: 24, color: "var(--text-2)" }}>Loading…</div>;
}
```

- [ ] **Step 4: Wire tsc + build script**

In `web/tsconfig.json` change the include line to:

```json
  "include": ["src", "share"]
```

In `web/package.json` change the build script to:

```json
    "build": "tsc -b && vite build && vite build --config vite.config.share.ts",
```

- [ ] **Step 5: Verify the build output**

Run: `cd web && npm run build && grep -o '/share/assets/[^"]*' dist/share/index.html`
Expected: at least one `/share/assets/share-….js` (and `.css`) match; `dist/index.html` and `dist/v2/index.html` still exist. No asset URL in `dist/share/index.html` may start with bare `/assets/`.

- [ ] **Step 6: Commit**

```bash
git add web/vite.config.share.ts web/share web/tsconfig.json web/package.json
git commit -m "feat(webui): share-page build entry with /share/-scoped assets"
```

---

### Task 4: Guest-page pure models — `geo.ts` + `feed.ts`

**Files:**
- Create: `web/share/src/geo.ts`
- Create: `web/share/src/feed.ts`
- Test: `web/share/src/geo.test.ts`
- Test: `web/share/src/feed.test.ts`

**Interfaces:**
- Produces (Tasks 6-7 rely on these exact exports):
  - geo: `haversineMeters(aLat, aLon, bLat, bLon): number`, `initialBearingDeg(aLat, aLon, bLat, bLon): number`, `cardinal(bearing: number): string`, `fmtDistance(meters: number): string`, `arrowRotation(bearingDeg: number, headingDeg: number | null): number | null`
  - feed: `interface FeedPoint {t, lat, lon}`, `interface Feed {points, last, expires_at, now, owner}`, `type FeedResult`, `FEED_POLL_MS = 10_000`, `STALE_MS = 120_000`, `tokenFromPath(pathname): string | null`, `isStale(last, nowMs): boolean`, `remainingLabel(expiresAt, nowMs): string`, `fetchFeed(token): Promise<FeedResult>`

- [ ] **Step 1: Write the failing tests**

Create `web/share/src/geo.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { arrowRotation, cardinal, fmtDistance, haversineMeters, initialBearingDeg } from "./geo";

describe("geo", () => {
  it("haversine: ~111 km per degree of latitude", () => {
    const m = haversineMeters(43.0, -87.9, 44.0, -87.9);
    expect(m).toBeGreaterThan(110_000);
    expect(m).toBeLessThan(112_500);
  });

  it("bearing: due north/east/south/west", () => {
    expect(Math.round(initialBearingDeg(43, -87.9, 44, -87.9))).toBe(0);
    expect(Math.round(initialBearingDeg(0, 0, 0, 1))).toBe(90);
    expect(Math.round(initialBearingDeg(44, -87.9, 43, -87.9))).toBe(180);
    expect(Math.round(initialBearingDeg(0, 1, 0, 0))).toBe(270);
  });

  it("cardinal names", () => {
    expect(cardinal(0)).toBe("N");
    expect(cardinal(44)).toBe("NE");
    expect(cardinal(90)).toBe("E");
    expect(cardinal(225)).toBe("SW");
    expect(cardinal(359)).toBe("N");
  });

  it("fmtDistance: feet under ~1000ft, miles above", () => {
    expect(fmtDistance(30)).toBe("100 ft");
    expect(fmtDistance(1000)).toBe("0.6 mi");
  });

  it("arrowRotation: relative to heading, null without compass", () => {
    expect(arrowRotation(90, 0)).toBe(90);
    expect(arrowRotation(10, 350)).toBe(20);
    expect(arrowRotation(350, 10)).toBe(340);
    expect(arrowRotation(90, null)).toBeNull();
  });
});
```

Create `web/share/src/feed.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { isStale, remainingLabel, tokenFromPath } from "./feed";

describe("feed model", () => {
  it("tokenFromPath accepts /share/<token> only", () => {
    expect(tokenFromPath("/share/AbC123xyz_-AbC123xyz_-AbC123xyz")).toBe(
      "AbC123xyz_-AbC123xyz_-AbC123xyz");
    expect(tokenFromPath("/share/AbC123xyz_-AbC123xyz_-AbC123xyz/")).toBe(
      "AbC123xyz_-AbC123xyz_-AbC123xyz");
    expect(tokenFromPath("/share/")).toBeNull();
    expect(tokenFromPath("/share/short")).toBeNull();
    expect(tokenFromPath("/share/index.html")).toBeNull();
    expect(tokenFromPath("/v2/")).toBeNull();
  });

  it("isStale after 120s or with no fix", () => {
    expect(isStale(null, 1_000_000)).toBe(true);
    expect(isStale({ t: 1_000_000 - 119_000, lat: 0, lon: 0 }, 1_000_000)).toBe(false);
    expect(isStale({ t: 1_000_000 - 121_000, lat: 0, lon: 0 }, 1_000_000)).toBe(true);
  });

  it("remainingLabel formats h/m/d", () => {
    expect(remainingLabel(1_000_000, 1_000_001)).toBe("expired");
    expect(remainingLabel(90_000 + 0, 0)).toBe("1m left");
    expect(remainingLabel(2 * 3_600_000 + 5 * 60_000, 0)).toBe("2h 5m left");
    expect(remainingLabel(3 * 86_400_000, 0)).toBe("3d left");
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd web && npx vitest run share/src`
Expected: FAIL — cannot resolve `./geo` / `./feed`.

- [ ] **Step 3: Implement `geo.ts`**

Create `web/share/src/geo.ts`:

```ts
/** Great-circle math for the find-me arrow. Angles in degrees, distances in meters. */

const R = 6_371_000;
const rad = (d: number) => (d * Math.PI) / 180;

export function haversineMeters(aLat: number, aLon: number, bLat: number, bLon: number): number {
  const dLat = rad(bLat - aLat);
  const dLon = rad(bLon - aLon);
  const s = Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}

/** Initial great-circle bearing from A to B, 0..360 clockwise from true north. */
export function initialBearingDeg(aLat: number, aLon: number, bLat: number, bLon: number): number {
  const y = Math.sin(rad(bLon - aLon)) * Math.cos(rad(bLat));
  const x = Math.cos(rad(aLat)) * Math.sin(rad(bLat)) -
    Math.sin(rad(aLat)) * Math.cos(rad(bLat)) * Math.cos(rad(bLon - aLon));
  return ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
}

export function cardinal(bearing: number): string {
  const names = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"];
  return names[Math.round((((bearing % 360) + 360) % 360) / 45) % 8];
}

export function fmtDistance(meters: number): string {
  const feet = meters * 3.28084;
  if (feet < 1000) return `${Math.max(10, Math.round(feet / 10) * 10)} ft`;
  return `${(feet / 5280).toFixed(1)} mi`;
}

/** Screen rotation for the arrow glyph: bearing relative to where the phone points.
 *  headingDeg null (no compass) → null: caller falls back to cardinal text + map line. */
export function arrowRotation(bearingDeg: number, headingDeg: number | null): number | null {
  if (headingDeg == null) return null;
  return (((bearingDeg - headingDeg) % 360) + 360) % 360;
}
```

- [ ] **Step 4: Implement `feed.ts`**

Create `web/share/src/feed.ts`:

```ts
/** Guest feed client + pure state helpers for the public find-me page. */

export interface FeedPoint { t: number; lat: number; lon: number }

export interface Feed {
  points: FeedPoint[];
  last: FeedPoint | null;
  expires_at: number;
  now: number;
  owner: string;
}

export type FeedResult =
  | { kind: "ok"; feed: Feed }
  | { kind: "ended" }    // 404: revoked or never existed — indistinguishable by design
  | { kind: "expired" }  // 410
  | { kind: "error" };   // network / 5xx

export const FEED_POLL_MS = 10_000;
export const STALE_MS = 120_000; // mirrors v2 LIVE_STALE_MS

/** /share/<token> (optional trailing slash). Tokens are token_urlsafe(24) = 32 chars;
 *  require >=16 so /share/index.html and stray short paths never look like tokens. */
export function tokenFromPath(pathname: string): string | null {
  const m = pathname.match(/^\/share\/([A-Za-z0-9_-]{16,})\/?$/);
  return m ? m[1] : null;
}

export function isStale(last: FeedPoint | null, nowMs: number): boolean {
  return last == null || nowMs - last.t > STALE_MS;
}

export function remainingLabel(expiresAt: number, nowMs: number): string {
  const left = expiresAt - nowMs;
  if (left <= 0) return "expired";
  const h = Math.floor(left / 3_600_000);
  if (h >= 48) return `${Math.floor(h / 24)}d left`;
  if (h >= 1) return `${h}h ${Math.floor((left % 3_600_000) / 60_000)}m left`;
  return `${Math.max(1, Math.floor(left / 60_000))}m left`;
}

export async function fetchFeed(token: string): Promise<FeedResult> {
  try {
    const r = await fetch(`/share/${token}/feed`);
    if (r.status === 404) return { kind: "ended" };
    if (r.status === 410) return { kind: "expired" };
    if (!r.ok) return { kind: "error" };
    return { kind: "ok", feed: (await r.json()) as Feed };
  } catch {
    return { kind: "error" };
  }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd web && npx vitest run share/src`
Expected: all pass. Then `npm test` (whole suite) — all pass.

- [ ] **Step 6: Commit**

```bash
git add web/share/src/geo.ts web/share/src/feed.ts \
        web/share/src/geo.test.ts web/share/src/feed.test.ts
git commit -m "feat(share-page): geo bearing/distance + feed state models"
```

---

### Task 5: `JourneyMap` optional guest marker + bearing line

**Files:**
- Modify: `web/src/v2/components/JourneyMap.tsx`

**Interfaces:**
- Consumes: existing `JourneyMap` internals (`mapRef`, `mapReady`, `live`).
- Produces: new optional prop `guest?: LivePos | null` (default absent → zero behavior change for v2 callers). When set: a blue dot at the guest's position and a dashed blue line from guest to the live chair position.

- [ ] **Step 1: Add the prop**

In `web/src/v2/components/JourneyMap.tsx`, extend the destructuring and type (add `guest = null` after `showTrail = true`, and `guest?: LivePos | null;` to the type literal):

```tsx
export function JourneyMap({ points, segKinds, hotspots, cursorIndex, theme, live, liveStale, fitKey, metric, emptyText, fill, showTrail = true, guest = null }: {
  points: TrackPoint[]; segKinds: SegKind[]; hotspots: Hotspot[]; cursorIndex: number;
  theme: "dark" | "light"; live: LivePos | null; liveStale?: boolean; fitKey: string;
  metric: "power" | "soc"; emptyText?: string; fill?: boolean; showTrail?: boolean;
  guest?: LivePos | null;
}) {
```

- [ ] **Step 2: Render guest layers**

Add two refs next to the existing marker refs, and a new effect after the live-marker effect:

```tsx
  const guestRef = useRef<L.CircleMarker | null>(null);
  const guestLineRef = useRef<L.Polyline | null>(null);
```

```tsx
  // Guest marker + bearing line (share page only): where the viewer is, and a dashed
  // line from them to the chair so "which way do I walk" is visible on the map.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (guestRef.current) { map.removeLayer(guestRef.current); guestRef.current = null; }
    if (guestLineRef.current) { map.removeLayer(guestLineRef.current); guestLineRef.current = null; }
    if (!guest) return;
    guestRef.current = L.circleMarker([guest.lat, guest.lon], {
      radius: 7, color: "#fff", weight: 2, fillColor: "#3b82f6", fillOpacity: 1,
    }).addTo(map);
    if (live) {
      guestLineRef.current = L.polyline(
        [[guest.lat, guest.lon], [live.lat, live.lon]],
        { color: "#3b82f6", weight: 2, dashArray: "6 6", opacity: 0.8 },
      ).addTo(map);
    }
  }, [guest, live, mapReady]);
```

- [ ] **Step 3: Verify no regression**

Run: `cd web && npm run build && npm test`
Expected: build + all tests pass (prop is optional; v2 callers unchanged).

- [ ] **Step 4: Commit**

```bash
git add web/src/v2/components/JourneyMap.tsx
git commit -m "feat(webui-v2): optional guest marker + bearing line on JourneyMap"
```

---

### Task 6: Guest find-me page (`App.tsx`)

**Files:**
- Modify: `web/share/src/App.tsx` (replace the Task 3 placeholder)

**Interfaces:**
- Consumes: Task 4's `feed.ts` exports; Task 5's `guest` prop; `JourneyMap` (`../../src/v2/components/JourneyMap`), `cleanTrack` (`../../src/v2/model/cleanTrack`), `LivePos` (`../../src/v2/model/live`), `TrackPoint` (`../../src/v2/track`), `SegKind` (`../../src/v2/model/journey`), `relAgo` (`../../src/util`).
- Produces: `<App />` (default export) — full-screen guest page; `ArrowPanel` import is added in Task 7 (this task stubs the slot with `null`).

- [ ] **Step 1: Implement the page**

Replace `web/share/src/App.tsx` with:

```tsx
import { useEffect, useMemo, useState } from "react";
import { JourneyMap } from "../../src/v2/components/JourneyMap";
import { cleanTrack } from "../../src/v2/model/cleanTrack";
import type { SegKind } from "../../src/v2/model/journey";
import type { LivePos } from "../../src/v2/model/live";
import type { TrackPoint } from "../../src/v2/track";
import { relAgo } from "../../src/util";
import {
  FEED_POLL_MS, fetchFeed, isStale, remainingLabel, tokenFromPath, type Feed,
} from "./feed";

type Status = "loading" | "ok" | "ended" | "expired" | "error";

export default function App() {
  const token = useMemo(() => tokenFromPath(window.location.pathname), []);
  const [feed, setFeed] = useState<Feed | null>(null);
  const [status, setStatus] = useState<Status>("loading");
  const [nowMs, setNowMs] = useState(() => Date.now());
  const [guest, setGuest] = useState<LivePos | null>(null);

  useEffect(() => {
    if (!token) { setStatus("ended"); return; }
    let alive = true;
    const load = () => fetchFeed(token).then((r) => {
      if (!alive) return;
      setNowMs(Date.now());
      if (r.kind === "ok") { setFeed(r.feed); setStatus("ok"); }
      else if (r.kind === "error") setStatus((s) => (s === "ok" ? "ok" : "error"));
      else setStatus(r.kind);
    });
    load();
    const t = setInterval(load, FEED_POLL_MS);
    return () => { alive = false; clearInterval(t); };
  }, [token]);

  if (!token || status === "ended") return <Message text="This share link isn't available." />;
  if (status === "expired") {
    return <Message text="This location share has expired. Ask for a new link." />;
  }
  if (status === "error") return <Message text="Can't reach the server — check your connection." />;
  if (status === "loading" || !feed) return <Message text="Loading…" />;

  const points: TrackPoint[] = cleanTrack(feed.points.map((p) => ({
    t: p.t, lat: p.lat, lon: p.lon, power_w: null, current_a: null, soc: null,
  })));
  const segKinds: SegKind[] = points.map(() => "active");
  const live: LivePos | null = feed.last
    ? { lat: feed.last.lat, lon: feed.last.lon, tsMs: feed.last.t } : null;
  const stale = isStale(feed.last, feed.now);
  const theme = document.documentElement.dataset.theme === "light" ? "light" : "dark";

  return (
    <div style={{ height: "100dvh", display: "flex", flexDirection: "column", overflow: "hidden" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 10, padding: "10px 14px",
        flexShrink: 0 }}>
        <span style={{ fontSize: 15, fontWeight: 600 }}>Following {feed.owner}</span>
        <span className="mono" style={{ marginLeft: "auto", fontSize: 11, color: "var(--text-3)" }}>
          {remainingLabel(feed.expires_at, nowMs)}
        </span>
      </div>
      <div style={{ flex: 1, minHeight: 0, position: "relative" }}>
        <JourneyMap points={points} segKinds={segKinds} hotspots={[]}
          cursorIndex={Math.max(0, points.length - 1)} theme={theme}
          live={live} liveStale={stale} fitKey={token} metric="power"
          emptyText="Waiting for GPS…" fill guest={guest} />
        <span className="mono" style={{ position: "absolute", top: 12, right: 12, zIndex: 1000,
          display: "flex", alignItems: "center", gap: 6, padding: "6px 10px", borderRadius: 8,
          background: "rgba(9,9,11,.72)", color: "#e4e4e7", fontSize: 11, letterSpacing: 1 }}>
          {stale ? (<>
            <span style={{ width: 8, height: 8, borderRadius: "50%", background: "var(--warn)" }} />
            LAST KNOWN{live ? ` · ${relAgo(live.tsMs, feed.now)}` : ""}
          </>) : (<>
            <span style={{ width: 8, height: 8, borderRadius: "50%", background: "var(--ok)",
              boxShadow: "0 0 0 3px rgba(34,197,94,.2)" }} />
            LIVE
          </>)}
        </span>
      </div>
      {/* Task 7 mounts <ArrowPanel target={live} onGuest={setGuest} /> here. */}
      {null && guest}
    </div>
  );
}

function Message({ text }: { text: string }) {
  return (
    <div style={{ minHeight: "100dvh", display: "flex", alignItems: "center",
      justifyContent: "center", padding: 24, textAlign: "center", color: "var(--text-2)" }}>
      {text}
    </div>
  );
}
```

Note: `{null && guest}` keeps `guest`/`setGuest` referenced so `tsc` (noUnusedLocals) passes until Task 7 wires the ArrowPanel. The trail renders uniform `var(--ok)` green — `dischargeColor(|null power|→0)` — which is the intended neutral, battery-blind trail.

- [ ] **Step 2: Build + typecheck**

Run: `cd web && npm run build`
Expected: passes; `dist/share/` regenerated.

- [ ] **Step 3: Manual smoke (local)**

```bash
docker compose -f server/docker-compose.dev.yml up -d
cd server && BMSMON_WEB_DIST=$PWD/../web/dist .venv/bin/python -m uvicorn app.main:app --port 8000 &
# mint a share against the local server (dev headers bypass may be off — use the DB directly):
.venv/bin/python - <<'EOF'
import asyncio, secrets
from app.auth.enroll import hash_code
from app.db.pool import create_pool
import time
async def main():
    pool = await create_pool()
    tok = secrets.token_urlsafe(24)
    now = int(time.time()*1000)
    async with pool.acquire() as conn:
        await conn.execute(
            "INSERT INTO location_shares (token_hash, name, created_by, created_at, expires_at) VALUES ($1,'dev','dev',$2,$3)",
            hash_code(tok), now, now + 3_600_000)
    print(f"http://localhost:8000/share/{tok}")
    await pool.close()
asyncio.run(main())
EOF
```

Open the printed URL: page loads with map + "Following Joely" header; an unknown token URL 404s; after revoking the row (`UPDATE location_shares SET revoked_at=...`), polls flip the page to "share link isn't available" within ~10 s. Kill the uvicorn afterwards.

- [ ] **Step 4: Commit**

```bash
git add web/share/src/App.tsx
git commit -m "feat(share-page): live find-me guest page (today-only trail + live marker)"
```

---

### Task 7: Direction arrow (`ArrowPanel`)

**Files:**
- Create: `web/share/src/Arrow.tsx`
- Modify: `web/share/src/App.tsx` (mount the panel)

**Interfaces:**
- Consumes: Task 4's geo exports; `LivePos` type.
- Produces: `ArrowPanel({ target, onGuest }: { target: LivePos | null; onGuest: (g: LivePos | null) => void })` — Tier 1 (geolocation → distance + cardinal + map line via `onGuest`) everywhere; Tier 2 (compass-rotated arrow) where device orientation is available.

- [ ] **Step 1: Implement the panel**

Create `web/share/src/Arrow.tsx`:

```tsx
import { useEffect, useRef, useState, type CSSProperties } from "react";
import type { LivePos } from "../../src/v2/model/live";
import { arrowRotation, cardinal, fmtDistance, haversineMeters, initialBearingDeg } from "./geo";

/** Direction-to-target panel. Tier 1 (all browsers): watch the guest's geolocation and
 *  show distance + compass-point toward the chair (App draws the map line via onGuest).
 *  Tier 2 (progressive): rotate a live arrow by device heading. iOS gates the compass
 *  behind DeviceOrientationEvent.requestPermission() (must be called from the tap);
 *  webkitCompassHeading is already degrees-from-north, absolute alpha is CCW so
 *  heading = 360 - alpha. No compass → arrow hidden, cardinal text stays. */
export function ArrowPanel({ target, onGuest }: {
  target: LivePos | null;
  onGuest: (g: LivePos | null) => void;
}) {
  const [on, setOn] = useState(false);
  const [denied, setDenied] = useState(false);
  const [pos, setPos] = useState<{ lat: number; lon: number } | null>(null);
  const [heading, setHeading] = useState<number | null>(null);
  const watchId = useRef<number | null>(null);

  const listen = () => {
    const onOrient = (e: DeviceOrientationEvent) => {
      const webkit = (e as DeviceOrientationEvent & { webkitCompassHeading?: number })
        .webkitCompassHeading;
      if (webkit != null) setHeading(webkit);
      else if (e.absolute && e.alpha != null) setHeading((360 - e.alpha) % 360);
    };
    window.addEventListener("deviceorientationabsolute", onOrient as EventListener);
    window.addEventListener("deviceorientation", onOrient as EventListener);
  };

  const start = () => {
    setOn(true);
    watchId.current = navigator.geolocation.watchPosition(
      (p) => {
        setPos({ lat: p.coords.latitude, lon: p.coords.longitude });
        onGuest({ lat: p.coords.latitude, lon: p.coords.longitude, tsMs: Date.now() });
      },
      () => setDenied(true),
      { enableHighAccuracy: true, maximumAge: 5000 },
    );
    const dm = DeviceOrientationEvent as unknown as { requestPermission?: () => Promise<string> };
    (dm.requestPermission ? dm.requestPermission().catch(() => "denied")
      : Promise.resolve("granted"))
      .then((state) => { if (state === "granted") listen(); });
  };

  useEffect(() => () => {
    if (watchId.current != null) navigator.geolocation.clearWatch(watchId.current);
  }, []);

  if (!on) {
    return (
      <div style={panel}>
        <button style={btn} onClick={start}>Point me there</button>
        <span style={{ fontSize: 11, color: "var(--text-4)" }}>
          Uses your location to show distance and direction.
        </span>
      </div>
    );
  }
  if (denied) {
    return (
      <div style={panel}>
        <span style={{ color: "var(--text-3)", fontSize: 12 }}>
          Location permission denied — enable it in your browser to get directions.
        </span>
      </div>
    );
  }
  if (!pos || !target) {
    return <div style={panel}><span style={{ color: "var(--text-3)", fontSize: 12 }}>Locating…</span></div>;
  }

  const meters = haversineMeters(pos.lat, pos.lon, target.lat, target.lon);
  const bearing = initialBearingDeg(pos.lat, pos.lon, target.lat, target.lon);
  const rot = arrowRotation(bearing, heading);
  return (
    <div style={panel}>
      {rot != null && (
        <span aria-hidden style={{ display: "inline-block", fontSize: 26, lineHeight: 1,
          transform: `rotate(${rot}deg)`, transition: "transform .2s" }}>↑</span>
      )}
      <span className="mono" style={{ fontSize: 14 }}>{fmtDistance(meters)} {cardinal(bearing)}</span>
      <span style={{ fontSize: 11, color: "var(--text-4)" }}>
        {rot != null ? "the arrow points toward them" : "direction is from north"}
      </span>
    </div>
  );
}

const panel: CSSProperties = {
  display: "flex", alignItems: "center", gap: 12, padding: "10px 14px",
  borderTop: "1px solid var(--border)", flexShrink: 0,
};
const btn: CSSProperties = {
  background: "var(--nav-active)", border: "1px solid var(--border)", color: "var(--text)",
  fontSize: 12, padding: "8px 14px", borderRadius: 7, cursor: "pointer",
};
```

- [ ] **Step 2: Mount it in App**

In `web/share/src/App.tsx`: add `import { ArrowPanel } from "./Arrow";` and replace the two placeholder lines

```tsx
      {/* Task 7 mounts <ArrowPanel target={live} onGuest={setGuest} /> here. */}
      {null && guest}
```

with:

```tsx
      <ArrowPanel target={live} onGuest={setGuest} />
```

- [ ] **Step 3: Build + tests**

Run: `cd web && npm run build && npm test`
Expected: pass.

- [ ] **Step 4: Commit**

```bash
git add web/share/src/Arrow.tsx web/share/src/App.tsx
git commit -m "feat(share-page): direction-to-target arrow (geolocation + optional compass)"
```

---

### Task 8: Share creation — API helpers + `ShareDialog` + Journey toolbar button

**Files:**
- Modify: `web/src/api.ts` (append helpers)
- Create: `web/src/v2/components/ShareDialog.tsx`
- Modify: `web/src/v2/views/JourneyView.tsx` (toolbar button + dialog mount)

**Interfaces:**
- Consumes: Task 1's endpoints; existing `j`/`isObj` helpers in `api.ts`.
- Produces (Task 9 relies on these):
  - `interface ShareRow { id: number; name: string; created_at: number; expires_at: number; revoked_at: number | null; last_access_ms: number | null; access_count: number }` (exported from `web/src/api.ts`)
  - `createShare(name: string, duration: "1h" | "1d" | "1w"): Promise<{ id: number; name: string; expires_at: number; path: string }>`
  - `getShares(): Promise<{ shares: ShareRow[] }>`
  - `revokeShare(id: number): Promise<unknown>`

- [ ] **Step 1: API helpers**

Append to `web/src/api.ts`:

```ts
export interface ShareRow {
  id: number;
  name: string;
  created_at: number;
  expires_at: number;
  revoked_at: number | null;
  last_access_ms: number | null;
  access_count: number;
}

export const createShare = async (
  name: string,
  duration: "1h" | "1d" | "1w",
): Promise<{ id: number; name: string; expires_at: number; path: string }> => {
  const r = await fetch("/web/shares", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name, duration }),
  }).then(j);
  if (!isObj(r) || typeof r.path !== "string") throw new Error("malformed /web/shares response");
  return r as { id: number; name: string; expires_at: number; path: string };
};

export const getShares = async (): Promise<{ shares: ShareRow[] }> => {
  const r = await fetch("/web/shares").then(j);
  if (!isObj(r) || !Array.isArray(r.shares)) throw new Error("malformed /web/shares response");
  return { shares: r.shares as ShareRow[] };
};

export const revokeShare = async (id: number): Promise<unknown> =>
  fetch(`/web/shares/${id}`, { method: "DELETE" }).then(j);
```

- [ ] **Step 2: ShareDialog**

Create `web/src/v2/components/ShareDialog.tsx`:

```tsx
import { useState, type CSSProperties } from "react";
import { createShare } from "../../api";

const DURATIONS = [
  { value: "1h", label: "1 hour" },
  { value: "1d", label: "1 day" },
  { value: "1w", label: "1 week" },
] as const;
type Duration = (typeof DURATIONS)[number]["value"];

/** Mint a public location-share link: name the recipient, pick a duration, then hand
 *  the URL to the native share sheet (mobile) or the clipboard (desktop). The URL is
 *  shown once — it cannot be recovered later (only sha256 is stored server-side). */
export function ShareDialog({ onClose }: { onClose: () => void }) {
  const [name, setName] = useState("");
  const [duration, setDuration] = useState<Duration>("1d");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [url, setUrl] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const copy = (full: string) =>
    navigator.clipboard.writeText(full).then(() => setCopied(true)).catch(() => {});

  const create = () => {
    setBusy(true);
    setErr(null);
    createShare(name.trim(), duration)
      .then((r) => {
        const full = window.location.origin + r.path;
        setUrl(full);
        if (navigator.share) {
          navigator.share({ title: "Live location", text: "Follow my live location", url: full })
            .catch(() => { /* sheet dismissed — the link stays on screen to copy */ });
        } else {
          copy(full);
        }
      })
      .catch((e) => setErr(
        e instanceof Error && (e.message === "401" || e.message === "403")
          ? "Not authorized — your session may have expired (admin required)."
          : "Couldn't create the share link — check the connection and try again."))
      .finally(() => setBusy(false));
  };

  return (
    <div style={backdrop} onClick={onClose}>
      <div style={card} onClick={(e) => e.stopPropagation()}>
        <div className="eyebrow" style={{ marginBottom: 12 }}>Share live location</div>
        {url == null ? (
          <>
            <label style={{ display: "block", fontSize: 12, color: "var(--text-2)", marginBottom: 6 }}>
              Who is this for?
            </label>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Dave"
              maxLength={80} autoFocus style={input} />
            <div style={{ display: "flex", gap: 8, margin: "14px 0" }}>
              {DURATIONS.map((d) => (
                <button key={d.value} onClick={() => setDuration(d.value)}
                  style={{ ...chip, ...(duration === d.value ? chipOn : null) }}>
                  {d.label}
                </button>
              ))}
            </div>
            {err && <div style={{ color: "var(--live)", fontSize: 12, marginBottom: 10 }}>{err}</div>}
            <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
              <button style={btn} onClick={onClose}>Cancel</button>
              <button style={{ ...btn, background: "var(--ok)", color: "#fff", border: "none" }}
                disabled={busy || name.trim() === ""} onClick={create}>
                {busy ? "Creating…" : "Create link"}
              </button>
            </div>
          </>
        ) : (
          <>
            <div style={{ fontSize: 12, color: "var(--text-2)", marginBottom: 8 }}>
              Link for <strong style={{ color: "var(--text)" }}>{name.trim()}</strong> — shown once,
              save it now:
            </div>
            <div className="mono" style={{ fontSize: 11, wordBreak: "break-all",
              padding: 10, background: "var(--panel-2)", border: "1px solid var(--border)",
              borderRadius: 7, marginBottom: 12 }}>{url}</div>
            <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", alignItems: "center" }}>
              {copied && <span style={{ fontSize: 11, color: "var(--ok)", marginRight: "auto" }}>Copied</span>}
              <button style={btn} onClick={() => copy(url)}>Copy</button>
              <button style={btn} onClick={onClose}>Done</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

const backdrop: CSSProperties = {
  position: "fixed", inset: 0, zIndex: 2000, background: "rgba(0,0,0,.55)",
  display: "flex", alignItems: "center", justifyContent: "center", padding: 20,
};
const card: CSSProperties = {
  width: "100%", maxWidth: 360, background: "var(--panel)",
  border: "1px solid var(--border-strong)", borderRadius: 10, padding: 18,
};
const input: CSSProperties = {
  width: "100%", padding: "8px 10px", fontSize: 13, color: "var(--text)",
  background: "var(--panel-2)", border: "1px solid var(--border)", borderRadius: 7,
};
const btn: CSSProperties = {
  background: "var(--nav-active)", border: "1px solid var(--border)", color: "var(--text)",
  fontSize: 12, padding: "6px 12px", borderRadius: 7, cursor: "pointer",
};
const chip: CSSProperties = { ...btn, flex: 1 };
const chipOn: CSSProperties = { background: "var(--ok)", color: "#fff", border: "none" };
```

- [ ] **Step 3: Toolbar button in Journey**

In `web/src/v2/views/JourneyView.tsx`:
1. Add imports: `import { ShareDialog } from "../components/ShareDialog";`
2. Add state next to the other `useState` calls in `JourneyView`: `const [shareOpen, setShareOpen] = useState(false);`
3. In the date-toolbar JSX, immediately after the `<Segmented<DateMode> ... />` element, add:

```tsx
  <button aria-label="Share live location" title="Share live location"
    style={stepBtnStyle()} onClick={() => setShareOpen(true)}>↗</button>
```

4. At the end of the component's top-level returned element (both branches share the toolbar, so put it just inside the outermost `<div>` right after the toolbar block):

```tsx
  {shareOpen && <ShareDialog onClose={() => setShareOpen(false)} />}
```

- [ ] **Step 4: Build + tests**

Run: `cd web && npm run build && npm test`
Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add web/src/api.ts web/src/v2/components/ShareDialog.tsx web/src/v2/views/JourneyView.tsx
git commit -m "feat(webui-v2): share-location dialog in the Journey toolbar"
```

---

### Task 9: Settings management panel (`SharesPanel`)

**Files:**
- Create: `web/src/v2/model/shares.ts`
- Test: `web/src/v2/model/shares.test.ts`
- Create: `web/src/v2/components/SharesPanel.tsx`
- Modify: `web/src/v2/views/SettingsView.tsx`

**Interfaces:**
- Consumes: Task 8's `getShares` / `revokeShare` / `ShareRow`.
- Produces: `shareStatus(s: ShareRow, nowMs): "active" | "expired" | "revoked"`, `remainingShort(expiresAt, nowMs): string`, `lastOpened(s: ShareRow, nowMs): string`; `<SharesPanel />`.

- [ ] **Step 1: Write the failing model tests**

Create `web/src/v2/model/shares.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import type { ShareRow } from "../../api";
import { lastOpened, remainingShort, shareStatus } from "./shares";

const row = (over: Partial<ShareRow>): ShareRow => ({
  id: 1, name: "Dave", created_at: 0, expires_at: 1_000_000,
  revoked_at: null, last_access_ms: null, access_count: 0, ...over,
});

describe("shares model", () => {
  it("status: revoked beats expired beats active", () => {
    expect(shareStatus(row({ revoked_at: 5 }), 10)).toBe("revoked");
    expect(shareStatus(row({}), 999_999)).toBe("active");
    expect(shareStatus(row({}), 1_000_000)).toBe("expired");
  });

  it("remainingShort: d / h / m granularity", () => {
    expect(remainingShort(0, 1)).toBe("expired");
    expect(remainingShort(3 * 86_400_000, 0)).toBe("3d left");
    expect(remainingShort(5 * 3_600_000, 0)).toBe("5h left");
    expect(remainingShort(90_000, 0)).toBe("1m left");
  });

  it("lastOpened: never / m / h / d", () => {
    expect(lastOpened(row({}), 0)).toBe("never opened");
    expect(lastOpened(row({ last_access_ms: 0 }), 30_000)).toBe("just now");
    expect(lastOpened(row({ last_access_ms: 0 }), 5 * 60_000)).toBe("5m ago");
    expect(lastOpened(row({ last_access_ms: 0 }), 3 * 3_600_000)).toBe("3h ago");
    expect(lastOpened(row({ last_access_ms: 0 }), 2 * 86_400_000)).toBe("2d ago");
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd web && npx vitest run src/v2/model/shares.test.ts`
Expected: FAIL — cannot resolve `./shares`.

- [ ] **Step 3: Implement the model**

Create `web/src/v2/model/shares.ts`:

```ts
import type { ShareRow } from "../../api";

export function shareStatus(s: ShareRow, nowMs: number): "active" | "expired" | "revoked" {
  if (s.revoked_at != null) return "revoked";
  return nowMs < s.expires_at ? "active" : "expired";
}

export function remainingShort(expiresAt: number, nowMs: number): string {
  const left = expiresAt - nowMs;
  if (left <= 0) return "expired";
  const d = Math.floor(left / 86_400_000);
  if (d >= 1) return `${d}d left`;
  const h = Math.floor(left / 3_600_000);
  if (h >= 1) return `${h}h left`;
  return `${Math.max(1, Math.floor(left / 60_000))}m left`;
}

export function lastOpened(s: ShareRow, nowMs: number): string {
  if (s.last_access_ms == null) return "never opened";
  const min = Math.floor((nowMs - s.last_access_ms) / 60_000);
  if (min < 1) return "just now";
  if (min < 60) return `${min}m ago`;
  const h = Math.floor(min / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}
```

- [ ] **Step 4: Run model tests to verify they pass**

Run: `cd web && npx vitest run src/v2/model/shares.test.ts`
Expected: PASS.

- [ ] **Step 5: The panel**

Create `web/src/v2/components/SharesPanel.tsx`:

```tsx
import { useEffect, useState, type CSSProperties } from "react";
import { getShares, revokeShare, type ShareRow } from "../../api";
import { lastOpened, remainingShort, shareStatus } from "../model/shares";

const errKind = (e: unknown): "auth" | "net" =>
  e instanceof Error && (e.message === "401" || e.message === "403") ? "auth" : "net";

const AUTH_MSG =
  "Not authorized — your session may have expired (admin required). Reload to sign in again.";

const btn: CSSProperties = {
  background: "var(--nav-active)", border: "1px solid var(--border)", color: "var(--text)",
  fontSize: 12, padding: "6px 12px", borderRadius: 7, cursor: "pointer",
};

/** Manage public location shares: who has a live link, whether they opened it, revoke.
 *  Links themselves are unrecoverable here by design (only sha256 is stored). */
export function SharesPanel() {
  const [shares, setShares] = useState<ShareRow[]>([]);
  const [loadErr, setLoadErr] = useState<"auth" | "net" | null>(null);
  const [actionErr, setActionErr] = useState<string | null>(null);
  const nowMs = Date.now();

  const refresh = () => getShares()
    .then((r) => { setShares(r.shares); setLoadErr(null); })
    .catch((e) => setLoadErr(errKind(e)));
  useEffect(() => { refresh(); }, []);

  const revoke = (id: number) => revokeShare(id)
    .then(() => { setActionErr(null); refresh(); })
    .catch((e) => setActionErr(errKind(e) === "auth" ? AUTH_MSG
      : "Couldn't revoke the share — check the connection and try again."));

  const statusCell = (s: ShareRow) => {
    const st = shareStatus(s, nowMs);
    if (st === "active") return <span style={{ color: "var(--ok)" }}>{remainingShort(s.expires_at, nowMs)}</span>;
    if (st === "revoked") return <span style={{ color: "var(--live)" }}>REVOKED</span>;
    return <span style={{ color: "var(--text-4)" }}>EXPIRED</span>;
  };

  if (loadErr === "auth") return <div style={{ fontSize: 12, color: "var(--text-3)" }}>{AUTH_MSG}</div>;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      <span style={{ fontSize: 13, color: "var(--text-2)" }}>
        Live location links you've shared. New links are created from the Journey page.
      </span>
      {actionErr && <div style={{ color: "var(--live)", fontSize: 12 }}>{actionErr}</div>}
      {loadErr === "net" && <div style={{ color: "var(--live)", fontSize: 12 }}>
        Couldn't load shares — check the connection.</div>}
      {shares.length === 0 && loadErr == null && (
        <span style={{ fontSize: 12, color: "var(--text-4)" }}>No active or recent shares.</span>
      )}
      {shares.length > 0 && (
        <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 12 }}>
          <thead><tr style={{ textAlign: "left" }}>
            <th className="eyebrow" style={{ paddingBottom: 6 }}>Name</th>
            <th className="eyebrow">Status</th>
            <th className="eyebrow">Opened</th>
            <th className="eyebrow" />
          </tr></thead>
          <tbody>
            {shares.map((s) => (
              <tr key={s.id} style={{ borderTop: "1px solid var(--border)",
                opacity: shareStatus(s, nowMs) === "active" ? 1 : 0.55 }}>
                <td style={{ padding: "8px 8px 8px 0" }}>{s.name}</td>
                <td className="mono">{statusCell(s)}</td>
                <td className="mono" style={{ color: "var(--text-3)" }}>
                  {lastOpened(s, nowMs)}{s.access_count > 0 ? ` · ×${s.access_count}` : ""}
                </td>
                <td style={{ textAlign: "right" }}>
                  {shareStatus(s, nowMs) === "active" && (
                    <button style={btn} onClick={() => revoke(s.id)}>Revoke</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
```

- [ ] **Step 6: Mount in Settings**

In `web/src/v2/views/SettingsView.tsx`: add `import { SharesPanel } from "../components/SharesPanel";` and, directly after the `<SettingsCard title="Devices"><DevicesPanel /></SettingsCard>` block, add:

```tsx
      <SettingsCard title="Location shares">
        <SharesPanel />
      </SettingsCard>
```

- [ ] **Step 7: Build + full web tests**

Run: `cd web && npm run build && npm test`
Expected: pass.

- [ ] **Step 8: Commit**

```bash
git add web/src/v2/model/shares.ts web/src/v2/model/shares.test.ts \
        web/src/v2/components/SharesPanel.tsx web/src/v2/views/SettingsView.tsx
git commit -m "feat(webui-v2): location-shares management panel in Settings"
```

---

### Task 10: Traefik `/share/` zone (qnap-nas-docker repo) — **CHECKPOINT before push**

**Files:**
- Modify: `~/qnap-nas-docker/bmsmon/docker-compose.yml` (labels on `bmsmon-api`)

**Interfaces:**
- Consumes: the existing `bmsmon` service + `bmsmon-header` middleware.
- Produces: `https://bmsmon.covert.life/share/*` reachable without Authentik and without the proxy secret.

- [ ] **Step 1: Add the router labels**

In `~/qnap-nas-docker/bmsmon/docker-compose.yml`, inside the `bmsmon-api` service `labels:`, directly after the API-zone block (`traefik.http.routers.bmsmon-api.middlewares: "bmsmon-header@docker"`), add:

```yaml
      # === Share zone: public location-share links — token-gated in the app, NO Authentik,
      # === no proxy-secret injection (the /share endpoints never trust identity headers).
      traefik.http.routers.bmsmon-share.entrypoints: websecure
      traefik.http.routers.bmsmon-share.rule: Host(`bmsmon.covert.life`) && PathPrefix(`/share/`)
      traefik.http.routers.bmsmon-share.priority: 100
      traefik.http.routers.bmsmon-share.service: bmsmon
      traefik.http.routers.bmsmon-share.tls: true
      traefik.http.routers.bmsmon-share.tls.certresolver: letsencrypt
      traefik.http.routers.bmsmon-share.tls.domains[0].main: bmsmon.covert.life
      traefik.http.routers.bmsmon-share.middlewares: "bmsmon-header@docker"
```

- [ ] **Step 2: Commit locally**

```bash
cd ~/qnap-nas-docker
git add bmsmon/docker-compose.yml
git commit -m "bmsmon: public /share/ Traefik zone for location-share links"
```

- [ ] **Step 3: CHECKPOINT — confirm with the user before pushing**

Pushing to this repo's `master` auto-deploys via the self-hosted runner. Confirm with the user, then:

```bash
git push origin master
```

Expected: the runner restarts `bmsmon-api`; afterwards `curl -s -o /dev/null -w '%{http_code}' https://bmsmon.covert.life/share/nonexistent-token-1234567890` returns `404` (not a 302 to Authentik).

---

### Task 11: Ship — docs, deploy, end-to-end verification

**Files:**
- Modify: `/home/joely/bmsmon/CLAUDE.md` (document the feature)
- Modify: `~/GoogleDrive/obsidian/notes/Bmsmon.md` (one-line status update)

**Interfaces:**
- Consumes: everything above, merged on `main`.

- [ ] **Step 1: Update CLAUDE.md**

Add a section after the "WebUI v2" section of `/home/joely/bmsmon/CLAUDE.md`:

```markdown
### Location sharing (public /share/ zone)

Time-limited public share links let a named guest follow the chair live:
`https://bmsmon.covert.life/share/<token>` (token = `secrets.token_urlsafe(24)`; only
sha256 stored in `location_shares`; link recoverable ONLY at creation). Traefik has a
third zone — `PathPrefix(/share/)`, priority 100, `bmsmon-header` only (no Authentik, no
proxy secret) — and the guest page is a **third Vite build** (`web/share/`,
`vite.config.share.ts`, `base:"/share/"`) so its assets stay inside the public zone.
Server: `app/routers/share.py` — `GET /share/{token}` (active → guest shell; expired →
friendly "ask for a new link" page; unknown/REVOKED → identical bare 404) and
`GET /share/{token}/feed` (today-only fleet GPS via `q.gps_track_all`, fields t/lat/lon
ONLY — never battery data; day window clamped server-side in the container TZ; 410 when
expired; updates last_access/access_count; no-store + no-referrer; per-IP
`share_limiter` 60/min). Admin CRUD on the Authentik zone: `POST/GET /web/shares`,
`DELETE /web/shares/{id}` (require_admin — a share grants unauthenticated access, same
trust class as enroll codes; listing keeps ended shares 7 days). WebUI: Journey toolbar
↗ opens `ShareDialog` (name + 1h/1d/1w → native share sheet, else clipboard);
`Settings › Location shares` (`SharesPanel`) lists name/remaining/last-opened/×count +
Revoke (kills a live guest within one 10 s poll). Guest page (`web/share/src/`): map +
today's neutral-green trail + pulsing/stale chair marker + "Following <owner>"
(`BMSMON_SHARE_OWNER`, default "Joely") + countdown, and a "Point me there" panel —
geolocation distance/cardinal + dashed guest→chair map line everywhere, compass-rotated
arrow where device orientation is available (iOS needs the permission tap).
```

- [ ] **Step 2: Update the Obsidian note**

Append to the status/features area of `~/GoogleDrive/obsidian/notes/Bmsmon.md`:

```markdown
- **Location sharing (2026-07):** public time-limited `/share/<token>` links (Authentik-bypassed Traefik zone) — guest "find me" live map (today only, no battery data), share sheet from Journey, revocation in Settings, direction-to-me arrow for guests.
```

- [ ] **Step 3: Commit docs + push bmsmon**

```bash
cd /home/joely/bmsmon
git add CLAUDE.md
git commit -m "docs: location-sharing feature notes"
git push origin main
```

Watch the image build: `gh run watch` (workflow `build-server.yml`). Expected: success, pushes `ghcr.io/mkeguy106/bmsmon-server:latest`.

- [ ] **Step 4: Deploy to the NAS**

```bash
ssh joely@ddnas02 'bash -lc "cd /share/bsv/docker-compose && \
  docker compose --env-file .env -f bmsmon/docker-compose.yml pull bmsmon-api && \
  docker compose --env-file .env -f bmsmon/docker-compose.yml up -d bmsmon-api"'
curl -fsS https://bmsmon.covert.life/api/v1/health
```

Expected: `{"status":"ok"}`. Schema applies automatically on container start.

- [ ] **Step 5: End-to-end verification (with the user)**

1. Desktop: Journey → ↗ → name "Test" + 1 hour → link shown + "Copied". Open the link in a private/incognito window (no Authentik session): guest page loads, map + "Following Joely" + countdown. **No battery data anywhere.**
2. `curl -s -o /dev/null -w '%{http_code}' https://bmsmon.covert.life/share/bogus-token-000000000000` → `404`.
3. Guest page on a phone: tap "Point me there" → distance + direction appear; on iOS the compass permission prompt shows; arrow rotates with the phone.
4. Phone (user's): Journey → ↗ → share sheet opens → send via Messages.
5. Settings → Location shares: the test share shows "opened X ago · ×N". Revoke it → the open guest page flips to "This share link isn't available." within ~10 s.
6. Wait for a short share to expire (or create a 1 h one and revoke instead): expired link shows the "ask for a new link" page.

- [ ] **Step 6: Mark the accuracy check-in untouched**

No changes to `Fleet.kt` constants or the range/charge estimators were made — the 2026-07-15 accuracy check-in is unaffected. Nothing to do; this step exists so the executor doesn't "helpfully" touch calibration constants.
