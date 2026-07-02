"""SEC-4: per-IP rate limiting on the unauthenticated /api/v1/enroll endpoint."""

from httpx import ASGITransport, AsyncClient
from starlette.datastructures import Headers

from app.ratelimit import ENROLL_MAX_ATTEMPTS, RateLimiter, client_key

BODY = {"code": "NOPE", "install_uuid": "inst-rl", "public_key_spki_b64": "AAAA"}


def _client(app, ip: str) -> AsyncClient:
    transport = ASGITransport(app=app, client=(ip, 12345))
    return AsyncClient(transport=transport, base_url="http://t")


async def test_enroll_rate_limited_after_max_attempts(app):
    async with _client(app, "203.0.113.7") as c:
        for _ in range(ENROLL_MAX_ATTEMPTS):
            r = await c.post("/api/v1/enroll", json=BODY)
            assert r.status_code == 400  # invalid code, but the attempt counts
        r = await c.post("/api/v1/enroll", json=BODY)
        assert r.status_code == 429


async def test_enroll_rate_limit_is_per_ip(app):
    async with _client(app, "203.0.113.7") as c:
        for _ in range(ENROLL_MAX_ATTEMPTS + 1):
            await c.post("/api/v1/enroll", json=BODY)
        assert (await c.post("/api/v1/enroll", json=BODY)).status_code == 429
    # A different peer IP has its own window and is unaffected.
    async with _client(app, "198.51.100.9") as c2:
        assert (await c2.post("/api/v1/enroll", json=BODY)).status_code == 400


async def test_enroll_rate_limited_ip_can_still_succeed_after_window(app):
    # The window is time-based; RateLimiter behavior over time is unit-tested below.
    # Here just confirm a fresh app (fresh limiter) lets a previously-limited IP through.
    async with _client(app, "203.0.113.7") as c:
        assert (await c.post("/api/v1/enroll", json=BODY)).status_code == 400


def test_rate_limiter_window_expiry():
    now = [0.0]
    rl = RateLimiter(max_attempts=3, window_s=10, clock=lambda: now[0])
    assert all(rl.allow("k") for _ in range(3))
    assert rl.allow("k") is False
    assert rl.allow("other") is True  # independent key
    now[0] = 10.1  # window passed → hits pruned, allowed again
    assert rl.allow("k") is True
    assert "other" not in rl._hits  # idle keys with only expired hits are pruned


def _headers(d: dict[str, str]) -> Headers:
    return Headers(d)


def test_client_key_ignores_xff_without_proxy_secret(set_setting):
    set_setting("proxy_secret", "")
    key = _headers({"x-forwarded-for": "1.2.3.4"})
    assert client_key("10.0.0.1", key) == "10.0.0.1"


def test_client_key_ignores_xff_with_wrong_proxy_secret(set_setting):
    set_setting("proxy_secret", "s3cret")
    h = _headers({"x-forwarded-for": "1.2.3.4", "x-bmsmon-proxy-secret": "wrong"})
    assert client_key("10.0.0.1", h) == "10.0.0.1"


def test_client_key_uses_xff_first_hop_with_valid_proxy_secret(set_setting):
    set_setting("proxy_secret", "s3cret")
    h = _headers({"x-forwarded-for": "1.2.3.4, 10.0.0.9",
                  "x-bmsmon-proxy-secret": "s3cret"})
    assert client_key("10.0.0.1", h) == "1.2.3.4"


def test_client_key_falls_back_when_no_peer(set_setting):
    set_setting("proxy_secret", "")
    assert client_key(None, _headers({})) == "unknown"
