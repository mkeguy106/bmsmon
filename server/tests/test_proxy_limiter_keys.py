"""SRV-20 (app half): behind a valid proxy secret, the /api/* limiters key on the real
client (the first X-Forwarded-For hop), so one noisy client can't 429 everyone. Prod gets
there once Traefik injects the secret on the bmsmon-api router (infra plan). Without it,
every /api/* caller is keyed as Traefik's own IP: one bucket for the whole internet."""
from app.ratelimit import RateLimiter

SECRET = "s3cret"
ENROLL = {"code": "NOPE", "install_uuid": "inst-xff", "public_key_spki_b64": "AAAA"}


def _via_proxy(xff: str) -> dict:
    return {"X-Bmsmon-Proxy-Secret": SECRET, "X-Forwarded-For": xff}


async def test_apikey_limiter_buckets_per_forwarded_client(app, client, set_setting):
    set_setting("proxy_secret", SECRET)
    app.state.apikey_limiter = RateLimiter(max_attempts=2, window_s=60)
    a, b = _via_proxy("203.0.113.7"), _via_proxy("198.51.100.9")
    for _ in range(2):
        assert (await client.get("/api/v1/groups", headers=a)).status_code == 401
    assert (await client.get("/api/v1/groups", headers=a)).status_code == 429
    # Same TCP peer (the proxy), different real client: its own bucket.
    assert (await client.get("/api/v1/groups", headers=b)).status_code == 401


async def test_enroll_limiter_buckets_per_forwarded_client(app, client, set_setting):
    set_setting("proxy_secret", SECRET)
    app.state.enroll_limiter = RateLimiter(max_attempts=2, window_s=300)
    a, b = _via_proxy("203.0.113.7, 10.0.0.9"), _via_proxy("198.51.100.9")
    for _ in range(2):
        assert (await client.post("/api/v1/enroll", json=ENROLL, headers=a)).status_code == 400
    assert (await client.post("/api/v1/enroll", json=ENROLL, headers=a)).status_code == 429
    assert (await client.post("/api/v1/enroll", json=ENROLL, headers=b)).status_code == 400


async def test_without_the_secret_forwarded_clients_share_one_bucket(app, client, set_setting):
    # What prod's /api/* zone does until the Traefik label lands: XFF is untrusted and
    # ignored, so every caller is the proxy's IP.
    set_setting("proxy_secret", SECRET)
    app.state.apikey_limiter = RateLimiter(max_attempts=2, window_s=60)
    a, b = {"X-Forwarded-For": "203.0.113.7"}, {"X-Forwarded-For": "198.51.100.9"}
    for _ in range(2):
        assert (await client.get("/api/v1/groups", headers=a)).status_code == 401
    assert (await client.get("/api/v1/groups", headers=b)).status_code == 429
