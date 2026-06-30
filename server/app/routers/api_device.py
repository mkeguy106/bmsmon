import base64
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import JSONResponse

from app.auth.enroll import hash_code
from app.db import queries as q
from app.db.pool import get_pool
from app.models import EnrollBody, EnrollResponse

router = APIRouter(prefix="/api/v1")


@router.get("/health")
async def health(pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        await conn.execute("SELECT 1")
    return JSONResponse({"status": "ok"})


@router.post("/enroll", response_model=EnrollResponse)
async def enroll(body: EnrollBody, pool=Depends(get_pool)):
    try:
        spki = base64.b64decode(body.public_key_spki_b64)
    except Exception:
        raise HTTPException(400, "bad public key")
    now = datetime.now(timezone.utc)
    async with pool.acquire() as conn:
        async with conn.transaction():
            code = await q.take_valid_code(conn, hash_code(body.code), now)
            if code is None:
                raise HTTPException(400, "invalid or expired code")
            device_id = await q.create_device(conn, body.install_uuid, spki, body.device_label)
            await q.mark_code_used(conn, code["code_hash"], device_id, now)
    return EnrollResponse(device_id=str(device_id))
