import uuid

from app.db import queries as q

USER = {"X-authentik-username": "joel",
        "X-authentik-groups": "Covert.life - Full App Access - User Group"}

CFG = {"profile_id": "redodo-beken-bk-ble-1.0", "cold_caution_c": 5, "hot_caution_c": 45,
       "cold_crit_c": -12, "hot_crit_c": 53, "unit": "F", "updated_at_ms": 1782865938602}


async def test_temp_config_requires_identity(client):
    r = await client.get("/web/temp-config")
    assert r.status_code == 401


async def test_temp_config_empty(client):
    r = await client.get("/web/temp-config", headers=USER)
    assert r.status_code == 200
    assert r.json()["configs"] == []


async def test_temp_config_returns_pushed_config(app, client):
    device_id = str(uuid.uuid4())
    async with app.state.pool.acquire() as conn:
        await conn.execute(
            "INSERT INTO devices (id, install_uuid, public_key_spki, label) VALUES ($1,$2,$3,$4)",
            device_id, "inst-w", b"\x00", "phone")
        await q.upsert_temp_config(conn, device_id, CFG)
    r = await client.get("/web/temp-config", headers=USER)
    assert r.status_code == 200
    cfgs = r.json()["configs"]
    assert len(cfgs) == 1
    c = cfgs[0]
    assert c["profile_id"] == "redodo-beken-bk-ble-1.0"
    assert c["cold_crit_c"] == -12
    assert c["hot_crit_c"] == 53
    assert c["unit"] == "F"
    assert c["updated_at_ms"] == 1782865938602
