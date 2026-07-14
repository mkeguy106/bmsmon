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
