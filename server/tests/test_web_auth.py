"""Identity + group gate for /web/* (SEC-13/SEC-20). Being authenticated is not enough:
viewers need BMSMON_VIEWER_GROUP (or the admin group), admin routes need
BMSMON_ADMIN_GROUP, and every check fails CLOSED."""
import logging

import pytest

from tests.identities import ADMIN_GROUP, ADMIN_H, OUTSIDER_H, VIEWER_GROUP, VIEWER_H, identity

SAMPLES_URL = "/web/samples?address=C8:47:80:15:67:44&from_ms=0&to_ms=1"
TRACK_URL = "/web/track?address=C8:47:80:15:67:44&from_ms=0&to_ms=1"

ADMIN_ONLY = [
    ("GET", SAMPLES_URL, None),
    ("GET", "/web/devices", None),
    ("POST", "/web/enroll-codes", None),
    ("DELETE", "/web/devices/00000000-0000-0000-0000-000000000000", None),
    ("GET", "/web/shares", None),
    ("POST", "/web/shares", {"name": "Dave", "duration": "1h"}),
    ("DELETE", "/web/shares/1", None),
    ("GET", "/web/api-keys", None),
    ("POST", "/web/api-keys", {"name": "w"}),
    ("DELETE", "/web/api-keys/00000000-0000-0000-0000-000000000000", None),
]


async def test_fleet_requires_identity(client):
    assert (await client.get("/web/fleet")).status_code == 401


async def test_fleet_ok_for_viewer(client):
    r = await client.get("/web/fleet", headers=VIEWER_H)
    assert r.status_code == 200
    assert r.json()["fleet"] == []


# Every viewer-level /web route (everything not in ADMIN_ONLY), with arguments a member
# gets a 200 for, so a 403 below can only come from the group gate.
VIEWER_ROUTES = [
    ("GET", "/web/fleet", None),
    ("GET", "/web/temp-config", None),
    ("GET", "/web/alert-config", None),
    ("GET", "/web/range-config", None),
    ("GET", "/web/map-config", None),
    ("GET", "/web/history", None),
    ("GET", "/web/trends?address=C8:47:80:15:67:44&from_ms=0&to_ms=1", None),
    ("GET", "/web/charge-sessions?address=C8:47:80:15:67:44", None),
    ("GET", TRACK_URL, None),
    ("GET", "/web/notes", None),
    ("POST", "/web/notes", {"base_id": "2012", "body": "hello"}),
]


@pytest.mark.parametrize("method,url,body", VIEWER_ROUTES)
async def test_viewer_route_is_open_to_a_member(client, method, url, body):
    r = await client.request(method, url, headers=VIEWER_H, json=body)
    assert r.status_code == 200, (method, url)


@pytest.mark.parametrize("method,url,body", VIEWER_ROUTES)
async def test_non_member_is_403_on_every_viewer_route(client, method, url, body):
    # SEC-13: any Authentik login used to be enough to read live + historical GPS.
    r = await client.request(method, url, headers=OUTSIDER_H, json=body)
    assert r.status_code == 403, (method, url)


@pytest.mark.parametrize("method,url,body", VIEWER_ROUTES)
async def test_near_miss_group_suffix_is_403_on_every_viewer_route(client, method, url, body):
    # Exact match: a group that merely STARTS with the viewer group's name is not it.
    h = identity("x", VIEWER_GROUP + " (old)")
    r = await client.request(method, url, headers=h, json=body)
    assert r.status_code == 403, (method, url)


async def test_user_with_no_groups_header_is_403(client):
    r = await client.get("/web/fleet", headers={"X-authentik-username": "nobody"})
    assert r.status_code == 403


async def test_group_match_is_exact_and_case_sensitive(client):
    # Fail closed: a case variant (e.g. the old lowercase spelling) is not membership.
    near_miss = identity("x", VIEWER_GROUP.swapcase())
    assert (await client.get("/web/fleet", headers=near_miss)).status_code == 403


async def test_viewer_group_found_among_several_pipe_separated(client):
    # Review Focus 1: Authentik sends every group, pipe-separated, in no particular order.
    h = identity("x", "Family Photos", VIEWER_GROUP, "Media")
    assert (await client.get("/web/fleet", headers=h)).status_code == 200


async def test_groups_split_on_pipe_only_never_on_commas(client):
    # Authentik joins groups with "|". A group whose NAME contains a comma must stay one
    # group, or "Friends, <admin group>" would read as membership of the admin group.
    h = identity("friend", "Friends, " + ADMIN_GROUP)
    assert (await client.get("/web/devices", headers=h)).status_code == 403
    assert (await client.get("/web/fleet", headers=h)).status_code == 403
    h = identity("friend", "Friends, " + VIEWER_GROUP)
    assert (await client.get("/web/fleet", headers=h)).status_code == 403


async def test_a_member_of_a_comma_named_group_is_still_found(client):
    h = identity("x", "Friends, Family", VIEWER_GROUP)
    assert (await client.get("/web/fleet", headers=h)).status_code == 200


async def test_admin_alone_counts_as_viewer(client):
    # The owner may sit only in the owner-only admin group; the dashboard must still open.
    assert (await client.get("/web/fleet", headers=ADMIN_H)).status_code == 200


async def test_empty_viewer_group_setting_fails_closed(client, set_setting):
    set_setting("viewer_group", "")
    assert (await client.get("/web/fleet", headers=VIEWER_H)).status_code == 403
    assert (await client.get("/web/fleet", headers=ADMIN_H)).status_code == 200


async def test_admin_only_routes_refuse_a_household_viewer(client):
    # SEC-20: being in the household access group no longer grants admin.
    for method, url, body in ADMIN_ONLY:
        r = await client.request(method, url, headers=VIEWER_H, json=body)
        assert r.status_code == 403, (method, url)


async def test_admin_only_routes_refuse_outsiders(client):
    for method, url, body in ADMIN_ONLY:
        r = await client.request(method, url, headers=OUTSIDER_H, json=body)
        assert r.status_code == 403, (method, url)


async def test_admin_can_mint_code(client):
    r = await client.post("/web/enroll-codes", headers=ADMIN_H)
    assert r.status_code == 200
    assert len(r.json()["code"]) == 20


async def test_samples_and_devices_admin_ok(client):
    r = await client.get(SAMPLES_URL, headers=ADMIN_H)
    assert r.status_code == 200 and r.json()["samples"] == []
    r = await client.get("/web/devices", headers=ADMIN_H)
    assert r.status_code == 200 and r.json()["devices"] == []


async def test_denials_are_logged_once_per_user(client, caplog):
    caplog.set_level(logging.WARNING, logger="app.auth.authentik")
    h = identity("stray-account-for-log-test", "Some Other Group")
    for _ in range(3):
        assert (await client.get("/web/fleet", headers=h)).status_code == 403
    hits = [r for r in caplog.records if "stray-account-for-log-test" in r.getMessage()]
    assert len(hits) == 1


# Proxy shared secret (BMSMON_PROXY_SECRET): when set, identity headers are only
# trusted if the reverse proxy also injected the matching X-Bmsmon-Proxy-Secret.

async def test_proxy_secret_missing_header_is_401(client, set_setting):
    set_setting("proxy_secret", "hunter2")
    assert (await client.get("/web/fleet", headers=VIEWER_H)).status_code == 401


async def test_proxy_secret_wrong_value_is_401(client, set_setting):
    set_setting("proxy_secret", "hunter2")
    r = await client.get("/web/fleet", headers={**VIEWER_H, "X-Bmsmon-Proxy-Secret": "nope"})
    assert r.status_code == 401


async def test_proxy_secret_correct_header_works(client, set_setting):
    set_setting("proxy_secret", "hunter2")
    r = await client.get("/web/fleet",
                         headers={**VIEWER_H, "X-Bmsmon-Proxy-Secret": "hunter2"})
    assert r.status_code == 200
    assert r.json()["fleet"] == []


async def test_proxy_secret_blocks_dev_trust_path_too(client, set_setting):
    # The secret is checked BEFORE any identity source, including dev-trust.
    set_setting("proxy_secret", "hunter2")
    set_setting("dev_trust_headers", True)
    assert (await client.get("/web/fleet")).status_code == 401
    r = await client.get("/web/fleet", headers={"X-Bmsmon-Proxy-Secret": "hunter2"})
    assert r.status_code == 200


async def test_proxy_secret_unset_ignores_header(client):
    assert (await client.get("/web/fleet", headers=VIEWER_H)).status_code == 200


# Dev-trust guard (SRV-8): the synthetic identity only activates when DATABASE_URL points
# at a local dev database, and it is a viewer AND an admin unless BMSMON_DEV_GROUPS says otherwise.

async def test_dev_trust_identity_is_viewer_and_admin(client, set_setting):
    set_setting("dev_trust_headers", True)  # test DB is localhost:5432, so this qualifies
    assert (await client.get("/web/fleet")).status_code == 200
    assert (await client.post("/web/enroll-codes")).status_code == 200


async def test_dev_trust_with_both_group_settings_empty_fails_closed(client, set_setting):
    # The synthetic identity's groups are [viewer_group, admin_group] = ["", ""]; an empty
    # setting must match nobody, not the empty strings the identity was built from.
    set_setting("dev_trust_headers", True)
    set_setting("viewer_group", "")
    set_setting("admin_group", "")
    assert (await client.get("/web/fleet")).status_code == 403
    assert (await client.get("/web/devices")).status_code == 403


async def test_dev_groups_override_is_still_gated(client, set_setting):
    set_setting("dev_trust_headers", True)
    set_setting("dev_groups", ["Some Other Group"])
    assert (await client.get("/web/fleet")).status_code == 403


async def test_dev_trust_ignored_when_db_not_local(client, set_setting):
    set_setting("dev_trust_headers", True)
    set_setting("database_url", "postgresql://bmsmon:pw@db-prod.internal.example:5432/bmsmon")
    assert (await client.get("/web/fleet")).status_code == 401
