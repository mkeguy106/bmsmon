import asyncio
import logging
from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles

from app.config import settings
from app.db.partitions import ensure_partitions_for_range
from app.db.pool import create_pool
from app.db.queries import scrub_expired_gps
from app.routers import api_device, share, web, ws

logger = logging.getLogger(__name__)

# GPS retention scrub cadence: once shortly after startup, then daily.
GPS_SCRUB_INITIAL_DELAY_S = 30
GPS_SCRUB_INTERVAL_S = 24 * 3600


async def run_gps_scrub(pool) -> int:
    """One GPS retention pass (SEC-12): scrub location columns on samples older than
    settings.gps_retention_days. Never deletes rows. Directly callable for tests."""
    async with pool.acquire() as conn:
        return await scrub_expired_gps(conn, settings.gps_retention_days)


async def _gps_scrub_loop(pool) -> None:
    await asyncio.sleep(GPS_SCRUB_INITIAL_DELAY_S)
    while True:
        try:
            n = await run_gps_scrub(pool)
            if n > 0:
                logger.info(
                    "GPS retention: scrubbed location off %d samples older than %d days",
                    n, settings.gps_retention_days,
                )
        except Exception:
            # A DB hiccup must never crash the app or stop future runs.
            logger.exception("GPS retention scrub failed; retrying in %d s", GPS_SCRUB_INTERVAL_S)
        await asyncio.sleep(GPS_SCRUB_INTERVAL_S)


# Vite emits content-hashed filenames into these dirs (one shared assets/ chunk pool for
# the v1 + v2 bundles — dist/v2 holds only its index.html — plus the share zone's own
# dist/share/assets, which reaches this mount because /share/{token} routes never match
# two-segment paths). Hashed content is safe to cache forever; the HTML shells must
# always revalidate so a deploy's new hashes land (no-cache still allows 304s via ETag).
_HASHED_ASSET_PREFIXES = ("assets/", "v2/assets/", "share/assets/")


class CachedStaticFiles(StaticFiles):
    """StaticFiles + Cache-Control: immutable for content-hashed assets, no-cache else."""

    async def get_response(self, path: str, scope):
        response = await super().get_response(path, scope)
        if response.status_code in (200, 304):
            if path.startswith(_HASHED_ASSET_PREFIXES):
                response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
            else:
                response.headers["Cache-Control"] = "no-cache"
        return response


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.pool = await create_pool()
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    month = 31 * 24 * 3600 * 1000
    async with app.state.pool.acquire() as conn:
        await ensure_partitions_for_range(conn, now_ms - month, now_ms + month)
    # GPS retention (SEC-12): skipped entirely when disabled (retention <= 0).
    gps_task = (
        asyncio.create_task(_gps_scrub_loop(app.state.pool))
        if settings.gps_retention_days > 0 else None
    )
    try:
        yield
    finally:
        if gps_task is not None:
            gps_task.cancel()
            try:
                await gps_task
            except asyncio.CancelledError:
                pass
        await app.state.pool.close()


def create_app() -> FastAPI:
    app = FastAPI(title="bmsmon", lifespan=lifespan)
    # Compress large JSON responses (fleet/history/track payloads). Websockets are
    # skipped by GZipMiddleware itself, and ingest is unaffected — its gzipped
    # *request* bodies are decompressed in the router, not by middleware.
    from starlette.middleware.gzip import GZipMiddleware
    app.add_middleware(GZipMiddleware, minimum_size=1024)
    from app.auth.device_jwt import JtiCache
    from app.caching import TouchThrottle, TtlCache
    from app.live.bus import LiveBus
    from app.ratelimit import RateLimiter
    app.state.jti_cache = JtiCache()
    app.state.bus = LiveBus()
    # Process-local perf state (single worker, SRV-8; app-scoped like the limiters so
    # each test app starts fresh). See routers/share.py for the TTL/interval rationale.
    from app.routers.share import TOUCH_INTERVAL_S, TRACK_CACHE_TTL_S
    app.state.share_track_cache = TtlCache(ttl_s=TRACK_CACHE_TTL_S)
    app.state.share_touch = TouchThrottle(interval_s=TOUCH_INTERVAL_S)
    # devices.last_seen_at write throttle (see routers/api_device.py).
    app.state.device_touch = TouchThrottle(interval_s=60.0)
    # SEC-4: per-IP limiter for the unauthenticated /api/v1/enroll (see app/ratelimit.py).
    app.state.enroll_limiter = RateLimiter()
    app.include_router(api_device.router)
    app.include_router(web.router)
    app.include_router(ws.router)
    # Per-IP limiter for the public /share zone: a guest page polls ~6-8/min, so
    # 60/min is invisible to legitimate use and throttles token scanning.
    app.state.share_limiter = RateLimiter(max_attempts=60, window_s=60)
    app.include_router(share.router)
    import os
    web_dist = os.environ.get("BMSMON_WEB_DIST", "/app/web/dist")
    if os.path.isdir(web_dist):
        app.mount("/", CachedStaticFiles(directory=web_dist, html=True), name="web")
    return app


app = create_app()
