from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from app.db.pool import get_pool

router = APIRouter(prefix="/api/v1")


@router.get("/health")
async def health(pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        await conn.execute("SELECT 1")
    return JSONResponse({"status": "ok"})
