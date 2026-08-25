"""Admin-gated API-key management behind the WebUI settings panel."""

from app.auth.api_key import hash_key

ADMIN = {"X-authentik-username": "joel",
         "X-authentik-groups": "Covert.life - Full App Access - User Group"}
NON_ADMIN = {"X-authentik-username": "rando", "X-authentik-groups": "Some Other Group"}


async def test_api_keys_require_admin(client):
    assert (await client.get("/web/api-keys", headers=NON_ADMIN)).status_code == 403
    assert (await client.post("/web/api-keys", headers=NON_ADMIN,
                              json={"name": "x"})).status_code == 403
    assert (await client.delete("/web/api-keys/abc", headers=NON_ADMIN)).status_code == 403


async def test_mint_returns_key_once_and_never_again(client):
    r = await client.post("/web/api-keys", headers=ADMIN, json={"name": "desktop widgets"})
    assert r.status_code == 200
    body = r.json()
    assert body["name"] == "desktop widgets"
    assert len(body["key"]) >= 40          # token_urlsafe(32) -> 43 chars

    listing = (await client.get("/web/api-keys", headers=ADMIN)).json()["keys"]
    assert len(listing) == 1
    row = listing[0]
    assert row["name"] == "desktop widgets"
    # Neither the key nor its hash may ever appear in the listing.
    assert "key" not in row and "key_hash" not in row
    assert body["key"] not in str(listing)
    assert hash_key(body["key"]) not in str(listing)


async def test_minted_key_actually_works_then_stops_on_revoke(client):
    minted = (await client.post("/web/api-keys", headers=ADMIN,
                                json={"name": "widget"})).json()
    key = minted["key"]

    assert (await client.get("/api/v1/groups", headers={"X-API-Key": key})).status_code == 200

    r = await client.delete(f"/web/api-keys/{minted['id']}", headers=ADMIN)
    assert r.status_code == 200 and r.json()["revoked"] == minted["id"]

    assert (await client.get("/api/v1/groups", headers={"X-API-Key": key})).status_code == 401


async def test_blank_name_rejected(client):
    r = await client.post("/web/api-keys", headers=ADMIN, json={"name": "   "})
    assert r.status_code == 422
