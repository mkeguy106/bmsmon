import json
import logging
import uuid

from app.db import queries as q
from tests.identities import ADMIN_H, VIEWER_H
from tests.test_ingest_jwt import _enroll_device, _keypair, _payload, _token
from tools import device_admin


async def _revoked_device(app):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    async with app.state.pool.acquire() as conn:
        await q.revoke_device(conn, device_id)
    return priv, spki, device_id


async def _row(app, device_id):
    async with app.state.pool.acquire() as conn:
        return dict(await conn.fetchrow("SELECT * FROM devices WHERE id=$1", device_id))


async def test_restore_clears_revoked_and_changes_nothing_else(app, client):
    _, spki, device_id = await _revoked_device(app)
    before = await _row(app, device_id)
    r = await client.post(f"/web/devices/{device_id}/restore", headers=ADMIN_H)
    assert r.status_code == 200
    assert r.json() == {"restored": device_id}
    after = await _row(app, device_id)
    assert after["revoked"] is False
    assert bytes(after["public_key_spki"]) == spki
    assert {**after, "revoked": True} == before


async def test_restore_is_audit_logged_with_the_admin(app, client, caplog):
    caplog.set_level(logging.INFO, logger="app.routers.web")
    _, _, device_id = await _revoked_device(app)
    r = await client.post(f"/web/devices/{device_id}/restore", headers=ADMIN_H)
    assert r.status_code == 200
    assert [r.getMessage() for r in caplog.records if r.name == "app.routers.web"] == [
        f"device restored: {device_id} by joel"]


async def test_restore_of_an_active_device_is_a_harmless_200(app, client):
    _, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    r = await client.post(f"/web/devices/{device_id}/restore", headers=ADMIN_H)
    assert r.status_code == 200


async def test_restore_is_admin_only(app, client):
    _, _, device_id = await _revoked_device(app)
    url = f"/web/devices/{device_id}/restore"
    assert (await client.post(url, headers=VIEWER_H)).status_code == 403
    assert (await client.post(url)).status_code in (401, 403)
    assert (await _row(app, device_id))["revoked"] is True


async def test_restore_unknown_and_malformed(client):
    r = await client.post(f"/web/devices/{uuid.uuid4()}/restore", headers=ADMIN_H)
    assert r.status_code == 404
    assert r.json() == {"detail": "device not found"}
    r = await client.post("/web/devices/not-a-uuid/restore", headers=ADMIN_H)
    assert r.status_code == 422


async def test_ingest_works_again_after_revoke_then_restore(app, client):
    priv, _, device_id = await _revoked_device(app)
    body = json.dumps(_payload()).encode()

    async def ingest():
        return await client.post("/api/v1/ingest", content=body, headers={
            "Authorization": f"Bearer {_token(priv, device_id, body)}"})

    assert (await ingest()).status_code == 401
    await client.post(f"/web/devices/{device_id}/restore", headers=ADMIN_H)
    assert (await ingest()).status_code == 200


async def test_enroll_of_a_revoked_device_says_restore_it(app, client):
    from tests.test_enroll import _pub_b64, _seed_code
    await _seed_code(app, "ONE")
    r = await client.post("/api/v1/enroll", json={
        "code": "ONE", "install_uuid": "inst-r", "public_key_spki_b64": _pub_b64()})
    async with app.state.pool.acquire() as conn:
        await q.revoke_device(conn, r.json()["device_id"])
    await _seed_code(app, "TWO")
    r = await client.post("/api/v1/enroll", json={
        "code": "TWO", "install_uuid": "inst-r", "public_key_spki_b64": _pub_b64()})
    assert r.status_code == 403
    assert r.json()["detail"] == "device revoked; restore it first"


async def test_cli_list_shows_no_key_material(app, capsys):
    _, spki, device_id = await _revoked_device(app)
    assert await device_admin.main(["list"]) == 0
    out = capsys.readouterr().out
    assert device_id in out and "REVOKED" in out
    assert spki.hex() not in out
    import base64
    assert base64.b64encode(spki).decode() not in out


def _audit(caplog) -> list[str]:
    return [r.getMessage() for r in caplog.records if r.name == "tools.device_admin"]


async def test_cli_restore(app, capsys, caplog):
    caplog.set_level(logging.INFO, logger="tools.device_admin")
    _, _, device_id = await _revoked_device(app)
    assert await device_admin.main(["restore", device_id]) == 0
    assert (await _row(app, device_id))["revoked"] is False
    assert await device_admin.main(["restore", str(uuid.uuid4())]) == 1
    assert _audit(caplog) == [f"device_admin: restored {device_id}"]


async def test_cli_delete_needs_confirmation(app, monkeypatch):
    _, _, device_id = await _revoked_device(app)
    monkeypatch.setattr("builtins.input", lambda _="": "n")
    assert await device_admin.main(["delete", device_id]) == 1
    assert await _row(app, device_id)


async def test_cli_delete_removes_only_the_device_row(app, client, monkeypatch):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body, headers={
        "Authorization": f"Bearer {_token(priv, device_id, body)}"})
    assert r.status_code == 200
    monkeypatch.setattr("builtins.input", lambda _="": "y")
    assert await device_admin.main(["delete", device_id]) == 0
    async with app.state.pool.acquire() as conn:
        assert await conn.fetchval("SELECT count(*) FROM devices WHERE id=$1", device_id) == 0
        assert await conn.fetchval("SELECT count(*) FROM samples") == 1


async def test_cli_delete_yes_flag(app, caplog):
    caplog.set_level(logging.INFO, logger="tools.device_admin")
    _, _, device_id = await _revoked_device(app)
    assert await device_admin.main(["delete", device_id, "--yes"]) == 0
    async with app.state.pool.acquire() as conn:
        assert await conn.fetchval("SELECT count(*) FROM devices") == 0
    assert _audit(caplog) == [f"device_admin: deleted {device_id}"]


async def test_cli_delete_of_a_code_enrolled_device_unbinds_its_code(app, client, monkeypatch):
    import base64
    from datetime import datetime, timedelta, timezone
    from app.auth.enroll import hash_code
    priv, spki = _keypair()
    async with app.state.pool.acquire() as conn:
        await q.create_enrollment_code(
            conn, hash_code("DELCODE"), "admin@covert.life",
            datetime.now(timezone.utc) + timedelta(minutes=10))
    r = await client.post("/api/v1/enroll", json={
        "code": "DELCODE", "install_uuid": "inst-del",
        "public_key_spki_b64": base64.b64encode(spki).decode()})
    assert r.status_code == 200
    device_id = r.json()["device_id"]
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body, headers={
        "Authorization": f"Bearer {_token(priv, device_id, body)}"})
    assert r.status_code == 200
    async with app.state.pool.acquire() as conn:
        bound = await conn.fetchval(
            "SELECT device_id::text FROM enrollment_codes WHERE code_hash=$1", hash_code("DELCODE"))
    assert bound == device_id
    monkeypatch.setattr("builtins.input", lambda _="": "y")
    assert await device_admin.main(["delete", device_id]) == 0
    async with app.state.pool.acquire() as conn:
        assert await conn.fetchval("SELECT count(*) FROM devices WHERE id=$1", device_id) == 0
        rows = await conn.fetch(
            "SELECT device_id FROM enrollment_codes WHERE code_hash=$1", hash_code("DELCODE"))
        assert len(rows) == 1 and rows[0]["device_id"] is None
        assert await conn.fetchval("SELECT count(*) FROM samples") == 1


async def test_cli_rejects_a_non_uuid_id_with_a_usage_error(app, capsys):
    import pytest
    with pytest.raises(SystemExit) as e:
        await device_admin.main(["delete", "not-a-uuid"])
    assert e.value.code == 2
    err = capsys.readouterr().err
    assert "not a device id" in err and "Traceback" not in err
