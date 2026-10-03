"""Behind a valid proxy secret, the /api/* limiters key on the real client (the first
X-Forwarded-For hop), so one noisy client can't 429 others. Without the secret,
X-Forwarded-For is untrusted and ignored."""
from app.ratelimit import RateLimiter

SECRET = "s3cret"
ENROLL = {"code": "NOPE", "install_uuid": "inst-xff", "public_key_spki_b64": "AAAA"}


def _via_proxy(xff: str, secret: str = SECRET) -> dict:
    return {"X-Bmsmon-Proxy-Secret": secret, "X-Forwarded-For": xff}


async def test_apikey_limiter_buckets_per_forwarded_client(app, client, set_setting):
    set_setting("proxy_secret", SECRET)
    app.state.apikey_limiter = RateLimiter(max_attempts=2, window_s=60)
    a, b = _via_proxy("203.0.113.7"), _via_proxy("198.51.100.9")
    for _ in range(2):
        assert (await client.get("/api/v1/groups", headers=a)).status_code == 401
    assert (await client.get("/api/v1/groups", headers=a)).status_code == 429
    # Different real client: its own bucket.
    assert (await client.get("/api/v1/groups", headers=b)).status_code == 401


async def test_enroll_limiter_buckets_per_forwarded_client(app, client, set_setting):
    set_setting("proxy_secret", SECRET)
    app.state.enroll_limiter = RateLimiter(max_attempts=2, window_s=300)
    a, b = _via_proxy("203.0.113.7, 10.0.0.9"), _via_proxy("198.51.100.9")
    for _ in range(2):
        assert (await client.post("/api/v1/enroll", json=ENROLL, headers=a)).status_code == 400
    assert (await client.post("/api/v1/enroll", json=ENROLL, headers=a)).status_code == 429
    assert (await client.post("/api/v1/enroll", json=ENROLL, headers=b)).status_code == 400


async def test_xff_without_proxy_secret_is_ignored(app, client, set_setting):
    # Secret configured but absent from the request: X-Forwarded-For is ignored,
    # so callers share the peer IP's bucket.
    set_setting("proxy_secret", SECRET)
    app.state.apikey_limiter = RateLimiter(max_attempts=2, window_s=60)
    a, b = {"X-Forwarded-For": "203.0.113.7"}, {"X-Forwarded-For": "198.51.100.9"}
    for _ in range(2):
        assert (await client.get("/api/v1/groups", headers=a)).status_code == 401
    assert (await client.get("/api/v1/groups", headers=b)).status_code == 429


async def test_first_xff_hop_is_used_not_last(app, client, set_setting):
    # With valid proxy secret, limiter keys on the first hop, not the last.
    set_setting("proxy_secret", SECRET)
    app.state.apikey_limiter = RateLimiter(max_attempts=2, window_s=60)
    # Both have the same trailing hop (10.0.0.9) but different first hops.
    a = _via_proxy("203.0.113.7, 10.0.0.9")
    b = _via_proxy("198.51.100.9, 10.0.0.9")
    for _ in range(2):
        assert (await client.get("/api/v1/groups", headers=a)).status_code == 401
    assert (await client.get("/api/v1/groups", headers=a)).status_code == 429
    # Different first hop: separate bucket.
    assert (await client.get("/api/v1/groups", headers=b)).status_code == 401


async def test_xff_ignored_with_wrong_proxy_secret(app, client, set_setting):
    # Wrong proxy secret: X-Forwarded-For is ignored, caller is keyed by peer IP.
    set_setting("proxy_secret", SECRET)
    app.state.apikey_limiter = RateLimiter(max_attempts=2, window_s=60)
    a = _via_proxy("203.0.113.7", secret="wrong")
    b = _via_proxy("198.51.100.9", secret="wrong")
    for _ in range(2):
        assert (await client.get("/api/v1/groups", headers=a)).status_code == 401
    # Same peer IP, different XFF claimed: still rate limited (XFF ignored).
    assert (await client.get("/api/v1/groups", headers=b)).status_code == 429
