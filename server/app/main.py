from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import FastAPI

from app.db.partitions import ensure_partitions_for_range
from app.db.pool import create_pool
from app.routers import api_device


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
    return app


app = create_app()
