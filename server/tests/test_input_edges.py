"""Garbage input on browser and enroll routes is a 422, never a 500: NUL bytes (Postgres
text cannot hold U+0000, so asyncpg refused to encode it), a malformed pack address, a
non-UUID id in a DELETE path, an out-of-range epoch. Device telemetry is different: its
NULs are stripped (models.TextOrNone), because the phone deletes a 4xx'd batch."""
import pytest
from httpx import ASGITransport, AsyncClient

from tests.identities import ADMIN_H, VIEWER_H

A = "C8:47:80:15:67:44"


def _client(app) -> AsyncClient:
    # raise_app_exceptions=False: a regression must show up as a 500 status, not as a
    # traceback inside the test runner.
    return AsyncClient(transport=ASGITransport(app=app, raise_app_exceptions=False),
                       base_url="http://t")


@pytest.mark.parametrize("path,body,headers", [
    ("/api/v1/enroll", {"code": "abc\x00", "install_uuid": "u1", "public_key_spki_b64": "AAAA"}, {}),
    ("/api/v1/enroll", {"code": "abc", "install_uuid": "u\x00", "public_key_spki_b64": "AAAA"}, {}),
    ("/api/v1/enroll", {"code": "abc", "install_uuid": "u2", "public_key_spki_b64": "AA\x00A"}, {}),
    ("/api/v1/enroll", {"code": "abc", "install_uuid": "u3", "public_key_spki_b64": "AAAA",
                        "device_label": "x\x00"}, {}),
    ("/web/notes", {"base_id": "2012", "body": "hi\x00"}, VIEWER_H),
    ("/web/notes", {"base_id": "20\x0012", "body": "x"}, VIEWER_H),
    ("/web/shares", {"name": "a\x00b", "duration": "1h"}, ADMIN_H),
    ("/web/api-keys", {"name": "a\x00b"}, ADMIN_H),
])
async def test_nul_in_a_body_string_is_422(app, path, body, headers):
    async with _client(app) as c:
        r = await c.post(path, json=body, headers=headers)
    assert r.status_code == 422, r.text


@pytest.mark.parametrize("path", [
    "/web/trends?address=A%00B&from_ms=0&to_ms=1000",
    "/web/track?address=A%00B&from_ms=0&to_ms=1000",
    "/web/charge-sessions?address=A%00B",
    "/web/samples?address=A%00B&from_ms=0&to_ms=1",
    "/web/trends?address=" + "A" * 33 + "&from_ms=0&to_ms=1",
    "/web/trends?address=A%20B&from_ms=0&to_ms=1",
    "/web/trends?address=&from_ms=0&to_ms=1",
    "/web/trends?address=A&from_ms=0&to_ms=9000000000000000000",
    "/web/trends?address=A&from_ms=-1&to_ms=0",
    "/web/samples?address=A&from_ms=0&to_ms=9000000000000000000",
])
async def test_bad_query_params_are_422(app, path):
    async with _client(app) as c:
        r = await c.get(path, headers=ADMIN_H)
    assert r.status_code == 422, r.text


@pytest.mark.parametrize("path", ["/web/devices/not-a-uuid", "/web/api-keys/not-a-uuid"])
async def test_malformed_ids_in_delete_paths_are_422(app, path):
    async with _client(app) as c:
        r = await c.delete(path, headers=ADMIN_H)
    assert r.status_code == 422, r.text


async def test_well_formed_requests_still_work(client):
    for path in (f"/web/trends?address={A}&from_ms=0&to_ms=1000",
                 f"/web/track?address={A}&from_ms=0&to_ms=1000",
                 f"/web/charge-sessions?address={A}",
                 f"/web/samples?address={A}&from_ms=0&to_ms=1"):
        assert (await client.get(path, headers=ADMIN_H)).status_code == 200, path
    zero = "00000000-0000-0000-0000-000000000000"
    r = await client.delete(f"/web/devices/{zero}", headers=ADMIN_H)
    assert r.status_code == 200 and r.json() == {"revoked": zero}
    r = await client.post("/web/notes", headers=VIEWER_H, json={"base_id": "2012", "body": "ok"})
    assert r.status_code == 200
