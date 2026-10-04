"""DATA-28 server side: the app build behind each device's uploads is recorded
(devices.user_agent) and shown on the admin device list."""
import json

from tests.identities import ADMIN_H
from tests.test_ingest_jwt import _enroll_device, _keypair, _payload, _token


async def _post(client, priv, device_id, path, payload, ua):
    body = json.dumps(payload).encode()
    headers = {"Authorization": f"Bearer {_token(priv, device_id, body)}", "User-Agent": ua}
    return await client.post(path, content=body, headers=headers)


def _cfg():
    return {"profile_id": "redodo", "cold_caution_c": 5, "hot_caution_c": 45, "cold_crit_c": -12,
            "hot_crit_c": 53, "unit": "F", "updated_at_ms": 1}


async def test_ingest_records_the_app_build(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    r = await _post(client, priv, device_id, "/api/v1/ingest", _payload(),
                    "bmsmon-android/1.4 (42; abc1234)")
    assert r.status_code == 200
    rows = (await client.get("/web/devices", headers=ADMIN_H)).json()["devices"]
    assert rows[0]["user_agent"] == "bmsmon-android/1.4 (42; abc1234)"


async def test_user_agent_is_cleaned_and_a_missing_one_keeps_the_last(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    # a tab is legal in a header value but not printable: it is stripped
    assert (await _post(client, priv, device_id, "/api/v1/config", _cfg(),
                        "ua\t-" + "x" * 300)).status_code == 200
    async with app.state.pool.acquire() as conn:
        ua = await conn.fetchval("SELECT user_agent FROM devices WHERE id = $1", device_id)
    assert ua.startswith("ua-x") and len(ua) == 200
    # /config touches unthrottled; an empty User-Agent must not wipe the stored one
    assert (await _post(client, priv, device_id, "/api/v1/config", _cfg(), "")).status_code == 200
    async with app.state.pool.acquire() as conn:
        assert await conn.fetchval("SELECT user_agent FROM devices WHERE id = $1", device_id) == ua
