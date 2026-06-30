from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import FastAPI

from app.db.partitions import ensure_partitions_for_range
from app.db.pool import create_pool
from app.routers import api_device, web, ws


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.pool = await create_pool()
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    month = 31 * 24 * 3600 * 1000
    async with app.state.pool.acquire() as conn:
        await ensure_partitions_for_range(conn, now_ms - month, now_ms + month)
    yield
    await app.state.pool.close()


def create_app() -> FastAPI:
    app = FastAPI(title="bmsmon", lifespan=lifespan)
    from app.auth.device_jwt import JtiCache
    from app.live.bus import LiveBus
    app.state.jti_cache = JtiCache()
    app.state.bus = LiveBus()
    app.include_router(api_device.router)
    app.include_router(web.router)
    app.include_router(ws.router)
    import os
    from fastapi.staticfiles import StaticFiles
    web_dist = os.environ.get("BMSMON_WEB_DIST", "/app/web/dist")
    if os.path.isdir(web_dist):
        app.mount("/", StaticFiles(directory=web_dist, html=True), name="web")
    return app


app = create_app()
