"""The phone-battery alarm threshold is shared by the phone and the WebUI; the most recent
change wins. The config push must never 422 over it."""
import json
import logging
import time

import pytest

from tests.identities import ADMIN_H, VIEWER_H
from tests.test_health_detail import URL, _key, _phone
from tests.test_ingest_jwt import _enroll_device, _keypair, _payload, _token
from tests.test_range_config import _cfg, _post_cfg

NOW = lambda: int(time.time() * 1000)  # noqa: E731
DEFAULT = {"low_pct": 75, "updated_at_ms": 0, "updated_by": "default"}


def _wire(j):
    """A web response minus can_edit (the phone-side block never has it)."""
    return {k: v for k, v in j.items() if k != "can_edit"}


@pytest.fixture
async def dev(app):
    priv, spki = _keypair()
    return priv, await _enroll_device(app, spki)


async def _push(client, dev, **fields):
    priv, did = dev
    return await _post_cfg(client, priv, did, {**_cfg(), **fields})


async def _web(client):
    return _wire((await client.get("/web/phone-alert", headers=VIEWER_H)).json())


async def test_default_when_no_row(client):
    r = await client.get("/web/phone-alert", headers=VIEWER_H)
    assert r.status_code == 200 and _wire(r.json()) == DEFAULT


async def test_can_edit_follows_admin(client):
    assert (await client.get("/web/phone-alert", headers=VIEWER_H)).json()["can_edit"] is False
    assert (await client.get("/web/phone-alert", headers=ADMIN_H)).json()["can_edit"] is True
    r = await client.put("/web/phone-alert", headers=ADMIN_H, json={"low_pct": 60})
    assert r.json()["can_edit"] is True


async def test_phone_block_has_no_can_edit(client, dev):
    r = await _push(client, dev)
    assert "can_edit" not in r.json()["phone_alert"]


async def test_admin_put_then_get(client):
    r = await client.put("/web/phone-alert", headers=ADMIN_H, json={"low_pct": 60})
    assert r.status_code == 200
    body = _wire(r.json())
    assert body["low_pct"] == 60 and body["updated_by"] == "web"
    assert abs(body["updated_at_ms"] - NOW()) < 5000
    assert await _web(client) == body


async def test_viewer_put_is_forbidden_and_changes_nothing(client):
    assert (await client.put("/web/phone-alert", headers=VIEWER_H,
                             json={"low_pct": 60})).status_code == 403
    assert await _web(client) == DEFAULT


async def test_put_requires_identity(client):
    assert (await client.put("/web/phone-alert", json={"low_pct": 60})).status_code == 401
    assert (await client.get("/web/phone-alert")).status_code == 401


@pytest.mark.parametrize("bad", [12, 0, 100, -5, "x", 10.5, True, 5, 96, {}])
async def test_put_invalid_is_422(client, bad):
    r = await client.put("/web/phone-alert", headers=ADMIN_H, json={"low_pct": bad})
    assert r.status_code == 422
    assert await _web(client) == DEFAULT


async def test_put_missing_field_is_422(client):
    assert (await client.put("/web/phone-alert", headers=ADMIN_H, json={})).status_code == 422


async def test_put_null_is_off(client):
    r = await client.put("/web/phone-alert", headers=ADMIN_H, json={"low_pct": None})
    assert r.status_code == 200 and r.json()["low_pct"] is None


async def test_phone_change_is_applied_and_returned(client, dev):
    t = NOW() - 1000
    r = await _push(client, dev, phone_low_pct=40, phone_low_pct_changed_ms=t)
    assert r.status_code == 200
    want = {"low_pct": 40, "updated_at_ms": t, "updated_by": "phone"}
    assert r.json()["phone_alert"] == want
    assert await _web(client) == want


async def test_older_phone_change_does_not_beat_newer_web_change(client, dev):
    await client.put("/web/phone-alert", headers=ADMIN_H, json={"low_pct": 80})
    web = await _web(client)
    r = await _push(client, dev, phone_low_pct=30, phone_low_pct_changed_ms=NOW() - 3_600_000)
    assert r.status_code == 200
    assert r.json()["phone_alert"] == web
    assert await _web(client) == web


async def test_newer_phone_change_beats_older_web_change(client, dev):
    await client.put("/web/phone-alert", headers=ADMIN_H, json={"low_pct": 80})
    t = NOW() + 0  # not older than the web stamp; clamp keeps it <= now
    time.sleep(0.01)
    r = await _push(client, dev, phone_low_pct=30, phone_low_pct_changed_ms=NOW())
    assert r.json()["phone_alert"]["low_pct"] == 30
    assert r.json()["phone_alert"]["updated_by"] == "phone"
    assert t <= r.json()["phone_alert"]["updated_at_ms"]


async def test_equal_time_is_not_strictly_newer(client, dev):
    t = NOW() - 5000
    await _push(client, dev, phone_low_pct=40, phone_low_pct_changed_ms=t)
    r = await _push(client, dev, phone_low_pct=50, phone_low_pct_changed_ms=t)
    assert r.json()["phone_alert"]["low_pct"] == 40


async def test_future_dated_phone_change_is_clamped_to_now(client, dev):
    r = await _push(client, dev, phone_low_pct=40,
                    phone_low_pct_changed_ms=NOW() + 10 * 86_400_000)
    pa = r.json()["phone_alert"]
    assert pa["low_pct"] == 40 and pa["updated_at_ms"] <= NOW()
    assert pa["updated_at_ms"] > NOW() - 5000


async def test_phone_off_via_null(client, dev):
    r = await _push(client, dev, phone_low_pct=None, phone_low_pct_changed_ms=NOW() - 10)
    assert r.json()["phone_alert"]["low_pct"] is None
    assert (await _web(client))["low_pct"] is None


@pytest.mark.parametrize("bad", [12, 0, 100, -5, "x", 10.5, True, [1], {"a": 1}])
async def test_invalid_phone_value_is_ignored_and_config_is_200(client, dev, bad, caplog):
    with caplog.at_level(logging.WARNING):
        r = await _push(client, dev, phone_low_pct=bad, phone_low_pct_changed_ms=NOW() - 10)
    assert r.status_code == 200
    assert r.json()["phone_alert"] == DEFAULT
    assert await _web(client) == DEFAULT
    msgs = [m.getMessage() for m in caplog.records if "phone_low_pct" in m.getMessage()]
    assert len(msgs) == 1 and str(bad) not in msgs[0].split("from device")[0]


@pytest.mark.parametrize("bad", [None, 0, -1, "x", 1.5, True, []])
async def test_invalid_changed_ms_is_ignored(client, dev, bad):
    extra = {} if bad is None else {"phone_low_pct_changed_ms": bad}
    r = await _push(client, dev, phone_low_pct=40, **extra)
    assert r.status_code == 200 and r.json()["phone_alert"] == DEFAULT


async def test_changed_ms_without_value_changes_nothing(client, dev):
    r = await _push(client, dev, phone_low_pct_changed_ms=NOW() - 10)
    assert r.status_code == 200 and r.json()["phone_alert"] == DEFAULT


async def test_old_app_config_without_new_fields_still_works(client, dev):
    r = await _push(client, dev)
    assert r.status_code == 200 and r.json()["ok"] is True
    assert r.json()["phone_alert"] == DEFAULT


async def test_ingest_response_carries_phone_alert(client, dev):
    priv, did = dev
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, did, body)}"})
    assert r.status_code == 200
    j = r.json()
    assert j["accepted"] == 1 and j["dropped"] == 0 and "last_seq" in j
    assert j["phone_alert"] == DEFAULT
    await client.put("/web/phone-alert", headers=ADMIN_H, json={"low_pct": 55})
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, did, body)}"})
    assert r.json()["phone_alert"]["low_pct"] == 55
    assert r.json()["phone_alert"]["updated_by"] == "web"
    assert r.headers["x-bmsmon-api"] == "1"


async def test_health_uses_the_row(app, client):
    async with app.state.pool.acquire() as conn:
        h = await _key(conn)
        await _phone(conn, fault=False, level=60)
    r = await client.get(URL + "?checks=phone_battery", headers=h)
    assert r.status_code == 503  # default 75
    await client.put("/web/phone-alert", headers=ADMIN_H, json={"low_pct": 50})
    r = await client.get(URL + "?checks=phone_battery", headers=h)
    assert r.status_code == 200 and r.json()["limits"]["phone_low_pct"] == 50
    await client.put("/web/phone-alert", headers=ADMIN_H, json={"low_pct": 65})
    r = await client.get(URL + "?checks=phone_battery", headers=h)
    assert r.status_code == 503 and r.json()["limits"]["phone_low_pct"] == 65


async def test_health_off_never_fails(app, client):
    async with app.state.pool.acquire() as conn:
        h = await _key(conn)
        await _phone(conn, fault=False, level=3)
    await client.put("/web/phone-alert", headers=ADMIN_H, json={"low_pct": None})
    r = await client.get(URL + "?checks=phone_battery", headers=h)
    assert r.status_code == 200 and r.json()["failing"] == []
    assert r.json()["limits"]["phone_low_pct"] is None


async def test_health_env_fallback_without_row(app, client, monkeypatch):
    from app.config import Settings
    from app.routers import api_device, web
    monkeypatch.setenv("BMSMON_PHONE_LOW_PCT", "50")
    s = Settings()
    for mod in (api_device, web):
        monkeypatch.setattr(mod, "settings", s)
    from app.routers import health
    monkeypatch.setattr(health, "settings", s)
    async with app.state.pool.acquire() as conn:
        h = await _key(conn)
        await _phone(conn, fault=False, level=60)
    r = await client.get(URL + "?checks=phone_battery", headers=h)
    assert r.status_code == 200 and r.json()["limits"]["phone_low_pct"] == 50
    assert (await client.get("/web/phone-alert", headers=VIEWER_H)).json()["low_pct"] == 50


async def test_default_deadman_response_is_unchanged(app, client):
    async with app.state.pool.acquire() as conn:
        h = await _key(conn)
    r = await client.get(URL, headers=h)
    assert "phone_alert" not in r.json()
