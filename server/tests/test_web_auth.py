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


NON_ADMIN = {"X-authentik-username": "rando", "X-authentik-groups": "Some Other Group"}
ADMIN_H = {"X-authentik-username": "joel", "X-authentik-groups": ADMIN}
SAMPLES_URL = "/web/samples?address=C8:47:80:15:67:44&from_ms=0&to_ms=1"


# /web/samples and /web/devices expose full GPS history + device enumeration → admin-only.

async def test_samples_requires_admin(client):
    assert (await client.get(SAMPLES_URL, headers=NON_ADMIN)).status_code == 403


async def test_samples_admin_ok(client):
    r = await client.get(SAMPLES_URL, headers=ADMIN_H)
    assert r.status_code == 200
    assert r.json()["samples"] == []


async def test_devices_requires_admin(client):
    assert (await client.get("/web/devices", headers=NON_ADMIN)).status_code == 403


async def test_devices_admin_ok(client):
    r = await client.get("/web/devices", headers=ADMIN_H)
    assert r.status_code == 200
    assert r.json()["devices"] == []


# Proxy shared secret (BMSMON_PROXY_SECRET): when set, identity headers are only
# trusted if the reverse proxy also injected the matching X-Bmsmon-Proxy-Secret.

async def test_proxy_secret_missing_header_is_401(client, set_setting):
    set_setting("proxy_secret", "hunter2")
    r = await client.get("/web/fleet", headers=ADMIN_H)
    assert r.status_code == 401


async def test_proxy_secret_wrong_value_is_401(client, set_setting):
    set_setting("proxy_secret", "hunter2")
    r = await client.get("/web/fleet", headers={**ADMIN_H, "X-Bmsmon-Proxy-Secret": "nope"})
    assert r.status_code == 401


async def test_proxy_secret_correct_header_works(client, set_setting):
    set_setting("proxy_secret", "hunter2")
    r = await client.get("/web/fleet", headers={**ADMIN_H, "X-Bmsmon-Proxy-Secret": "hunter2"})
    assert r.status_code == 200
    assert r.json()["fleet"] == []


async def test_proxy_secret_blocks_dev_trust_path_too(client, set_setting):
    # the secret is checked BEFORE any identity source, including dev-trust
    set_setting("proxy_secret", "hunter2")
    set_setting("dev_trust_headers", True)
    assert (await client.get("/web/fleet")).status_code == 401
    r = await client.get("/web/fleet", headers={"X-Bmsmon-Proxy-Secret": "hunter2"})
    assert r.status_code == 200


async def test_proxy_secret_unset_ignores_header(client):
    # default (unset) = feature off: current prod behavior preserved
    r = await client.get("/web/fleet", headers=ADMIN_H)
    assert r.status_code == 200
