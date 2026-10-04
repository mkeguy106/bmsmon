"""SRV-19: a genuine token is accepted while the server's clock is within 10 minutes of
the phone's, either way (the 2026-09-16 NAS step was 585 s). Replay stays bounded: the jti
cache remembers a token until it can no longer be accepted."""
import json
import time
import uuid

import jwt
import pytest

from app.auth.device_jwt import (EXP_LEEWAY_S, IAT_LEEWAY_S, JtiCache, JwtError, body_hash,
                                 verify_body, verify_token)
from app.routers.api_device import AUTH_REASON_HEADER as REASON
from tests.test_auth_reasons import _token_at
from tests.test_ingest_jwt import _enroll_device, _keypair, _payload


def test_window_is_ten_minutes_each_way():
    assert IAT_LEEWAY_S == 600 and EXP_LEEWAY_S == 600


@pytest.mark.parametrize("iat_offset,accepted", [
    (590, True),    # server 590 s BEHIND the phone: iat is in the server's future
    (610, False),   # beyond IAT_LEEWAY_S
    (-650, True),   # server 650 s AHEAD: exp = now - 590, inside EXP_LEEWAY_S
    (-700, False),  # exp = now - 640: too old
])
async def test_acceptance_window(app, client, iat_offset, accepted):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    now = int(time.time())
    tok = _token_at(priv, device_id, body, iat=now + iat_offset, exp=now + iat_offset + 60)
    r = await client.post("/api/v1/ingest", content=body, headers={"Authorization": f"Bearer {tok}"})
    if accepted:
        assert r.status_code == 200, r.text
        assert abs(app.state.auth_stats.last_ok_skew_s + iat_offset) <= 2
    else:
        # Both refusals stay clock_skew under the split leeway: +610 is our own iat check,
        # -700 is PyJWT's ExpiredSignatureError. Both measure the skew.
        assert r.status_code == 401 and r.headers[REASON] == "clock_skew"
        fail = app.state.auth_stats.last_failure
        assert fail["reason"] == "clock_skew" and abs(fail["skew_s"] + iat_offset) <= 2


def test_a_token_not_yet_valid_by_nbf_is_still_clock_skew():
    """With PyJWT's own iat check off, nbf is the one claim left that raises its
    ImmatureSignatureError. It must still name the clocks, not the credentials."""
    priv, spki = _keypair()
    now = int(time.time())
    claims = {"sub": str(uuid.uuid4()), "iat": now, "nbf": now + EXP_LEEWAY_S + 60,
              "exp": now + EXP_LEEWAY_S + 120, "jti": "n", "bh": body_hash(b"{}")}
    with pytest.raises(JwtError) as e:
        verify_token(jwt.encode(claims, priv, algorithm="ES256"), spki)
    assert e.value.reason == "clock_skew" and abs(e.value.skew_s) <= 2


@pytest.mark.parametrize("iat_offset", [590, -650])
async def test_a_token_replayed_inside_the_widened_window_is_a_replay(app, client, iat_offset):
    """The widened window must not widen replay: a token accepted at either edge is
    refused when presented again."""
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    now = int(time.time())
    tok = _token_at(priv, device_id, body, iat=now + iat_offset, exp=now + iat_offset + 60)
    h = {"Authorization": f"Bearer {tok}"}
    assert (await client.post("/api/v1/ingest", content=body, headers=h)).status_code == 200
    r = await client.post("/api/v1/ingest", content=body, headers=h)
    assert r.status_code == 401 and r.headers[REASON] == "replay"


@pytest.mark.parametrize("iat,reason", [
    (float("nan"), "bad_signature"),   # JSON NaN: "bad claim types"
    (float("inf"), "bad_signature"),   # JSON Infinity
    (10 ** 400, "clock_skew"),         # a finite integer, just absurdly far ahead
], ids=["nan", "inf", "huge"])
async def test_an_unusable_iat_is_a_401_not_a_500(app, client, iat, reason):
    """PyJWT's own iat check (which ran int(iat)) is off now, so ours must refuse what it
    refused, and never crash: float() of a huge integer iat would overflow."""
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    now = int(time.time())
    tok = _token_at(priv, device_id, body, iat=iat, exp=now + 60)
    r = await client.post("/api/v1/ingest", content=body, headers={"Authorization": f"Bearer {tok}"})
    assert r.status_code == 401 and r.headers[REASON] == reason


def test_a_jti_is_remembered_until_its_token_can_no_longer_be_accepted():
    t = [1000.0]
    cache = JtiCache(clock=lambda: t[0])
    claims = {"jti": "j", "exp": 1060, "bh": body_hash(b"x")}
    verify_body(claims, b"x", cache)
    t[0] = 1060 + EXP_LEEWAY_S - 1
    cache.seen("other", t[0] + 60)   # a prune runs at the late clock and must keep "j"
    with pytest.raises(JwtError, match="replay"):
        verify_body(claims, b"x", cache)
    assert cache.contains("j")
    t[0] = 1060 + EXP_LEEWAY_S       # where PyJWT refuses it as expired (exp <= now - leeway)
    assert not cache.contains("j")


def test_a_token_whose_window_closes_before_its_body_is_verified_is_refused():
    """Stage 1 passed just before exp + EXP_LEEWAY_S and the body finished just after: the
    token is refused on the same clock the cache uses, and nothing is remembered."""
    t = [1000.0]
    cache = JtiCache(clock=lambda: t[0])
    claims = {"jti": "late", "iat": 1000, "exp": 1060, "bh": body_hash(b"x")}
    t[0] = 1060 + EXP_LEEWAY_S
    with pytest.raises(JwtError) as e:
        verify_body(claims, b"x", cache)
    assert e.value.reason == "clock_skew" and e.value.skew_s is not None
    assert "late" not in cache._seen


def test_jti_cache_prunes_expired_entries_at_most_once_a_second():
    t = [0.0]
    cache = JtiCache(clock=lambda: t[0])
    for i in range(1000):
        cache.seen(f"j{i}", 10.0)
    t[0] = 11.0
    cache.seen("fresh", 100.0)
    assert set(cache._seen) == {"fresh"}
    cache.seen("brief", 11.5)
    t[0] = 11.9                      # "brief" has expired, but the last prune was 0.9 s ago
    cache.seen("later", 100.0)
    assert set(cache._seen) == {"fresh", "brief", "later"}


def test_an_expired_but_unpruned_jti_is_not_a_replay():
    t = [0.0]
    cache = JtiCache(clock=lambda: t[0])
    cache.seen("j", 0.2)
    t[0] = 0.5                       # inside PRUNE_INTERVAL_S: no prune ran
    assert cache.seen("j", 1.0) is False
