import asyncio
import logging
import os
import re
from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import FastAPI
from fastapi.responses import RedirectResponse
from fastapi.staticfiles import StaticFiles

from app.config import (CARTO_KEY_ENV, PHONE_LOW_PCT_ENV, parse_carto_key,
                        parse_phone_low_pct, settings)
from app.db.partitions import PRECREATE_AHEAD_MS, precreate_partitions
from app.db.pool import create_pool
from app.db.queries import scrub_expired_gps
from app.db.rollup import run_rollup_pass
from app.maintenance import maintenance_loop
from app.routers import api_device, api_widget, health, share, web, ws

logger = logging.getLogger(__name__)

LOG_FORMAT = "%(asctime)s %(levelname)s %(name)s %(message)s"
_LOG_HANDLER_NAME = "bmsmon"


# Timer-polled routes whose SUCCESSES carry no diagnostic value: Docker's healthcheck and the
# uptime monitor, the desktop widgets, and the guest share feed (every 4 s per guest). Their
# 2xx/3xx lines filled the 5 MB docker log cap in about a day and rotated away the lines that
# matter. Errors on these routes, and every other route, are still logged.
_QUIET_PATHS = frozenset({"/api/v1/health", "/api/v1/health/detail", "/api/v1/groups"})
_QUIET_SHARE_FEED = re.compile(r"^/share/[^/]+/feed$")


class QuietAccessLogFilter(logging.Filter):
    """Drops uvicorn access lines for successful timer polls (see _QUIET_PATHS)."""

    def filter(self, record: logging.LogRecord) -> bool:
        args = record.args
        if not isinstance(args, tuple) or len(args) < 5:
            return True
        status = args[4]
        if not isinstance(status, int) or status >= 400:
            return True
        path = str(args[2]).split("?", 1)[0]
        return not (path in _QUIET_PATHS or _QUIET_SHARE_FEED.match(path))


def configure_logging() -> None:
    """SRV-21/C3: give the `app` logger hierarchy its own INFO handler. Under uvicorn the
    root logger has no handlers and sits at WARNING, so `logger.info` lines (rollup, GPS
    scrub) were discarded and warnings printed bare via logging.lastResort. Idempotent
    (create_app runs once per test). propagate stays True so pytest's caplog, which hooks
    the root logger, still sees app records; uvicorn attaches no root handler, so lines
    are not printed twice in prod. Also installs QuietAccessLogFilter on uvicorn.access
    (uvicorn configures that logger before it imports the app; dictConfig keeps filters)."""
    access = logging.getLogger("uvicorn.access")
    if not any(isinstance(f, QuietAccessLogFilter) for f in access.filters):
        access.addFilter(QuietAccessLogFilter())
    log = logging.getLogger("app")
    log.setLevel(logging.INFO)
    if any(h.get_name() == _LOG_HANDLER_NAME for h in log.handlers):
        return
    handler = logging.StreamHandler()
    handler.set_name(_LOG_HANDLER_NAME)
    handler.setFormatter(logging.Formatter(LOG_FORMAT))
    log.addHandler(handler)


def log_carto_key_status() -> None:
    """One startup line when the maps will run without a CARTO key: INFO when
    BMSMON_CARTO_KEY is unset (so a forgotten map.env on the NAS shows in the log, not only
    as placeholder tiles), WARNING when it is set but was dropped as invalid (config.py
    parse_carto_key). Silent for a valid key. Runs once per create_app(), i.e. once per
    process in prod, after configure_logging so it carries the normal log format. It names
    the variable and the reason and NEVER the value: the key is a credential, and the logs
    are not secret."""
    raw = os.environ.get(CARTO_KEY_ENV)
    if raw is None:
        logger.info("%s not set: maps will show CARTO's key-required tiles", CARTO_KEY_ENV)
        return
    _key, problem = parse_carto_key(raw)
    if problem:
        logger.warning("%s ignored: %s. The Journey and share maps will show CARTO's "
                       "keyless placeholder tiles.", CARTO_KEY_ENV, problem)


def log_phone_low_pct_status() -> None:
    """One startup WARNING when BMSMON_PHONE_LOW_PCT is set but invalid (the phone_battery
    check then uses 75). Silent when unset or valid."""
    raw = os.environ.get(PHONE_LOW_PCT_ENV)
    _pct, problem = parse_phone_low_pct(raw)
    if problem:
        logger.warning("%s ignored: %s. Using %d.", PHONE_LOW_PCT_ENV, problem,
                       settings.phone_low_pct)


# GPS retention scrub cadence: once shortly after startup, then daily.
GPS_SCRUB_INITIAL_DELAY_S = 30
GPS_SCRUB_INTERVAL_S = 24 * 3600

# Analytics rollup cadence (SRV-14): first pass shortly after startup (this is also
# where the one-time backfill of an existing DB runs, chunked by month inside the
# pass), then every 15 min. Each pass re-rolls the trailing 48 h (late-arrival heal).
ROLLUP_INITIAL_DELAY_S = 20
ROLLUP_INTERVAL_S = 15 * 60


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


async def run_rollup(pool) -> int:
    """One samples_rollup pass (SRV-14): roll all closed 30-min buckets up to the
    high-water mark + re-roll the trailing 48 h. Directly callable for tests."""
    async with pool.acquire() as conn:
        return await run_rollup_pass(conn)


async def _rollup_loop(pool) -> None:
    await asyncio.sleep(ROLLUP_INITIAL_DELAY_S)
    while True:
        try:
            n = await run_rollup(pool)
            if n > 0:
                logger.info("samples_rollup: upserted %d bucket rows", n)
        except Exception:
            # A DB hiccup must never crash the app or stop future runs.
            logger.exception("samples_rollup pass failed; retrying in %d s", ROLLUP_INTERVAL_S)
        await asyncio.sleep(ROLLUP_INTERVAL_S)


# Vite emits content-hashed filenames into these dirs (one shared assets/ chunk pool for
# the v2 + v1 bundles — v2 is the default at dist/index.html and dist/v1 holds only its
# index.html — plus the share zone's own dist/share/assets, which reaches this mount
# because /share/{token} routes never match two-segment paths). Hashed content is safe to
# cache forever; the HTML shells must always revalidate so a deploy's new hashes land
# (no-cache still allows 304s via ETag) — that is also what lets a UI swap at "/" land
# without a cache-bust step.
_HASHED_ASSET_PREFIXES = ("assets/", "v1/assets/", "share/assets/")


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
    async with app.state.pool.acquire() as conn:
        # A month that gives up on a lock wait is logged and skipped, never fails the boot;
        # the maintenance pass retries it (maintenance.MAINTENANCE_INITIAL_DELAY_S).
        await precreate_partitions(conn, now_ms - PRECREATE_AHEAD_MS, now_ms + PRECREATE_AHEAD_MS)
    tasks = [asyncio.create_task(_rollup_loop(app.state.pool)),
             asyncio.create_task(maintenance_loop(app.state.pool))]
    # GPS retention (SEC-12): skipped entirely when disabled (retention <= 0).
    if settings.gps_retention_days > 0:
        tasks.append(asyncio.create_task(_gps_scrub_loop(app.state.pool)))
    try:
        yield
    finally:
        for t in tasks:
            t.cancel()
        for t in tasks:
            try:
                await t
            except asyncio.CancelledError:
                pass
        await app.state.pool.close()


def create_app() -> FastAPI:
    configure_logging()
    log_carto_key_status()
    log_phone_low_pct_status()
    app = FastAPI(title="bmsmon", lifespan=lifespan)
    from starlette.middleware.gzip import GZipMiddleware

    from app.middleware import (DB_ERROR_HANDLED_TYPES, DB_UNAVAILABLE_LOG_INTERVAL_S,
                                ApiMarkerMiddleware, BodySizeLimitMiddleware,
                                db_error_handler, marked_internal_error)
    # Unhandled exceptions -> a 500 that still carries the C1 marker (see the handler).
    app.add_exception_handler(Exception, marked_internal_error)
    # DB outages are answered inside the app (no re-raise, so no uvicorn traceback).
    for exc_type in DB_ERROR_HANDLED_TYPES:
        app.add_exception_handler(exc_type, db_error_handler)
    # MIDDLEWARE ORDER: Starlette wraps in REVERSE order of add_middleware, so the LAST
    # call is the outermost layer. ApiMarkerMiddleware must stay last (outermost) so every
    # response below it — including middleware-generated ones — gets X-Bmsmon-Api.
    #
    # Compress large JSON responses (fleet/history/track payloads). Websockets are
    # skipped by GZipMiddleware itself, and ingest is unaffected — its gzipped
    # *request* bodies are decompressed in the router, not by middleware.
    app.add_middleware(GZipMiddleware, minimum_size=1024)
    # SEC-17: cap every route's request body (wraps GZip; sits inside the marker).
    app.add_middleware(BodySizeLimitMiddleware)
    app.add_middleware(ApiMarkerMiddleware)  # keep LAST
    from app.auth.device_jwt import JtiCache
    from app.caching import TouchThrottle, TtlCache
    from app.live.bus import LiveBus
    from app.ratelimit import RateLimiter
    app.state.jti_cache = JtiCache()
    # Device-auth outcomes of KNOWN devices (routers/api_device.py), for health/detail.
    from app.observability import AuthStats
    app.state.auth_stats = AuthStats()
    app.state.bus = LiveBus()
    # Process-local perf state (single worker, SRV-8; app-scoped like the limiters so
    # each test app starts fresh). See routers/share.py for the TTL/interval rationale.
    from app.routers.share import LAST_FIX_CACHE_TTL_S, TOUCH_INTERVAL_S, TRACK_CACHE_TTL_S
    app.state.share_track_cache = TtlCache(ttl_s=TRACK_CACHE_TTL_S)
    app.state.share_discharge_cache = TtlCache(ttl_s=TRACK_CACHE_TTL_S)
    # C5: the share marker's 48 h last-fix lookback, used only while today's trail is
    # empty; its own 5 min TTL (see LAST_FIX_CACHE_TTL_S for why that is safe).
    app.state.share_last_fix_cache = TtlCache(ttl_s=LAST_FIX_CACHE_TTL_S)
    # Last base the guest dock resolved to — rung 3 of the share ladder ("parked: stay
    # put"). Memory only: on restart the dock falls back to the discharge hold/freshest
    # sample, which is exactly the cold-start behaviour the ladder is written for.
    app.state.share_active_base = None
    app.state.share_touch = TouchThrottle(interval_s=TOUCH_INTERVAL_S)
    # devices.last_seen_at write throttle (see routers/api_device.py).
    app.state.device_touch = TouchThrottle(interval_s=60.0)
    # device id -> (fault, plugged, monotonic time of the last phone-power write).
    app.state.phone_power_cache = {}
    # One "database unavailable" WARNING per exception class per interval (middleware.py).
    app.state.db_unavailable_log = TouchThrottle(interval_s=DB_UNAVAILABLE_LOG_INTERVAL_S)
    # C3: invalid-sample/range-row WARNINGs, at most once per device per kind per interval.
    app.state.reject_log = TouchThrottle(interval_s=api_device.REJECT_LOG_INTERVAL_S)
    # SEC-4: per-IP limiter for the unauthenticated /api/v1/enroll (see app/ratelimit.py).
    app.state.enroll_limiter = RateLimiter()
    # Widgets poll on a timer with a key they already hold, so legitimate traffic
    # never trips this; it exists to make key guessing pointless. Roomier than the
    # enroll limiter because four widgets share one desktop's IP.
    app.state.apikey_limiter = RateLimiter(max_attempts=240, window_s=60)
    # SEC-18: per-DEVICE upload budget (keyed by device_id after the signature verifies,
    # never by IP — see INGEST_MAX_PER_MIN for why it can't throttle an outbox drain).
    from app.ratelimit import INGEST_MAX_PER_MIN, INGEST_WINDOW_S
    app.state.ingest_limiter = RateLimiter(max_attempts=INGEST_MAX_PER_MIN,
                                           window_s=INGEST_WINDOW_S)
    app.include_router(api_device.router)
    app.include_router(api_widget.router)
    app.include_router(health.router)
    app.include_router(web.router)
    app.include_router(ws.router)
    # Per-IP limiter for the public /share zone. A guest page polls every 4 s = 15/min,
    # so 150/min keeps ~10 guests behind one IP (CGNAT) working while still bounding
    # load; a 192-bit token is infeasible to scan at any of these rates, and since the
    # poll went incremental each request is a cache slice rather than a query.
    app.state.share_limiter = RateLimiter(max_attempts=150, window_s=60)
    app.include_router(share.router)
    # v2 moved from /v2/ to / on 2026-08-04 (v1 now lives at /v1/). Keep old bookmarks and
    # any phone home-screen shortcut working. 307 rather than 308 because browsers cache
    # permanent redirects hard and this stays reversible. Registered before the "/" mount so
    # it wins over the static tree. Deliberately NOT /v2/{p:path}: v2 has no client-side
    # routing so there are no deep links to catch, and a catch-all would swallow
    # /v2/assets/* — nothing is emitted there today, but it would be a trap if that changed.
    @app.get("/v2", include_in_schema=False)
    @app.get("/v2/", include_in_schema=False)
    async def v2_moved_to_root() -> RedirectResponse:
        return RedirectResponse("/", status_code=307)

    web_dist = os.environ.get("BMSMON_WEB_DIST", "/app/web/dist")
    if os.path.isdir(web_dist):
        app.mount("/", CachedStaticFiles(directory=web_dist, html=True), name="web")
    return app


app = create_app()
