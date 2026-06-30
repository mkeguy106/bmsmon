from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, Query

from app.auth.authentik import AuthUser, current_user, require_admin
from app.auth.enroll import generate_code, hash_code
from app.db import queries as q
from app.db.pool import get_pool
from app.models import MintCodeResponse
from app.routers.ws import _jsonable

router = APIRouter(prefix="/web")


@router.get("/fleet")
async def fleet(user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        return {"fleet": _jsonable(await q.fleet_snapshot(conn))}


@router.get("/samples")
async def samples(address: str, from_ms: int = Query(...), to_ms: int = Query(...),
                  user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        return {"samples": _jsonable(await q.samples_range(conn, address, from_ms, to_ms))}


@router.get("/devices")
async def devices(user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        return {"devices": _jsonable(await q.list_devices(conn))}


@router.post("/enroll-codes", response_model=MintCodeResponse)
async def mint_code(user: AuthUser = Depends(require_admin), pool=Depends(get_pool)):
    code = generate_code()
    expires = datetime.now(timezone.utc) + timedelta(minutes=10)
    async with pool.acquire() as conn:
        await q.create_enrollment_code(conn, hash_code(code), user.username, expires)
    return MintCodeResponse(code=code, expires_at=expires.isoformat())


@router.delete("/devices/{device_id}")
async def revoke(device_id: str, user: AuthUser = Depends(require_admin), pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        await q.revoke_device(conn, device_id)
    return {"revoked": device_id}
