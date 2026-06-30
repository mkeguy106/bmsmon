ADMIN = "Covert.life - Full App Access - User Group"


async def test_fleet_requires_identity(client):
    r = await client.get("/web/fleet")
    assert r.status_code == 401


async def test_fleet_ok_with_authentik_headers(client):
    r = await client.get("/web/fleet", headers={
        "X-authentik-username": "joel", "X-authentik-groups": ADMIN})
    assert r.status_code == 200
    assert r.json()["fleet"] == []


async def test_mint_code_requires_admin_group(client):
    r = await client.post("/web/enroll-codes", headers={
        "X-authentik-username": "rando", "X-authentik-groups": "Some Other Group"})
    assert r.status_code == 403


async def test_admin_can_mint_code(client):
    r = await client.post("/web/enroll-codes", headers={
        "X-authentik-username": "joel", "X-authentik-groups": ADMIN})
    assert r.status_code == 200
    assert len(r.json()["code"]) == 20
