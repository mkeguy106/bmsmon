import asyncio
import json
import logging
import secrets
import time
from datetime import datetime, timedelta, timezone
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Path, Query, Response
from fastapi.responses import JSONResponse

from app.auth.api_key import hash_key as hash_api_key
from app.auth.authentik import AuthUser, current_user, is_admin, require_admin
from app.auth.enroll import generate_code, hash_code
from app.charge_sessions import detect_charge_sessions
from app.config import settings
from app.db import queries as q
from app.db.pool import get_pool
from app.models import (ApiKeyCreateBody, ApiKeyCreateResponse, MintCodeResponse, NoteBody,
                        OkResponse, PhoneAlertBody, ShareCreateBody, ShareCreateResponse)
from app.routers.api_device import ADDRESS_MAX_LEN
from app.util import jsonable

router = APIRouter(prefix="/web")

logger = logging.getLogger(__name__)

# SEC-13: /web/track returns GPS history (the same data class as the admin-only, 7-day
# /web/samples), so its span is bounded. Legit callers (web/src/v2/useTrack.ts via the
# Journey and Command views) ask for one local day (<= 25 h), the live day's
# incremental tail, or a Journey RANGE of whole local days: a calendar month across the
# DST fall-back night is 31 d + 1 h. Wider is refused (400), NOT clamped. Silently
# returning a truncated track would draw a wrong map.
TRACK_MAX_SPAN_MS = 31 * 86_400_000 + 3_600_000
# SRV-23: the History view asks for 30 days; 90 bounds a hand-written request.
CHARGE_SESSIONS_MAX_DAYS = 90
# Plausible epoch-ms ceiling (2100-01-01 UTC, same bound as SampleIn._clip_motion_at):
# keeps to_timestamp() inside Postgres' range, so garbage params are a 422, never a 500.
_EPOCH_MS_MAX = 4_102_444_800_000

# Query params that name a pack follow the ingest address rule (api_device._address_ok):
# 1..ADDRESS_MAX_LEN printable non-space ASCII. Nothing else can name a stored pack, and a
# NUL would reach Postgres as an unencodable parameter (a 500), so anything else is a 422.
Address = Annotated[str, Query(min_length=1, max_length=ADDRESS_MAX_LEN, pattern=r"^[!-~]+$")]
# Epoch-ms window params, bounded so to_timestamp()/fromtimestamp() can never overflow.
EpochMs = Annotated[int, Query(ge=0, le=_EPOCH_MS_MAX)]
# A UUID in a path: malformed ids are a 422 instead of a Postgres cast error (a 500).
PathUuid = Annotated[UUID, Path()]


def _f(v):
    return float(v) if v is not None else None


@router.get("/fleet")
async def fleet(user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        return {"fleet": jsonable(await q.fleet_snapshot(conn))}


@router.get("/temp-config")
async def temp_config(user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    """Read-only mirror of the temperature-alert thresholds the phone pushed (one-way)."""
    async with pool.acquire() as conn:
        return {"configs": jsonable(await q.get_temp_config_all(conn))}


@router.get("/alert-config")
async def alert_config(user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    """Read-only mirror of the capacity-alert config the phone pushed (one-way).

    Returns the SOC threshold at which a low pack seizes the main stage. Defaults to
    {seize_soc: null, alerts_on: true, updated_at_ms: 0} when the phone hasn't pushed one."""
    async with pool.acquire() as conn:
        cfg = await q.get_alert_config(conn)
    if cfg is None:
        return {"seize_soc": None, "alerts_on": True, "updated_at_ms": 0}
    return {"seize_soc": cfg["seize_soc"], "alerts_on": cfg["alerts_on"],
            "updated_at_ms": cfg["updated_at_ms"]}


@router.get("/phone-alert")
async def phone_alert_get(user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    """The shared phone-battery alarm threshold (viewers may read it)."""
    async with pool.acquire() as conn:
        state = await q.get_phone_alert(conn, settings.phone_low_pct)
    return {**state, "can_edit": is_admin(user)}


@router.put("/phone-alert")
async def phone_alert_put(body: PhoneAlertBody, user: AuthUser = Depends(require_admin),
                          pool=Depends(get_pool)):
    """Change the threshold (admin only: it controls paging). Stamped with server time, so
    it beats any earlier phone change; a later phone change beats it."""
    async with pool.acquire() as conn:
        await q.upsert_phone_alert(conn, body.low_pct, int(time.time() * 1000), "web")
        state = await q.get_phone_alert(conn, settings.phone_low_pct)
    return {**state, "can_edit": True}


@router.get("/range-config")
async def range_config(user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    """Read-only mirror of the learned discharge-range bands the phone pushed (one-way)."""
    async with pool.acquire() as conn:
        return {"configs": jsonable(await q.get_range_config_all(conn))}


@router.get("/map-config")
async def map_config(user: AuthUser = Depends(current_user)):
    """Runtime basemap config for the Journey map: the CARTO key from BMSMON_CARTO_KEY, or
    null when it is unset or invalid (the map then shows CARTO's placeholder tiles). It
    lives only in the NAS env, never in the public repo or image, so the browser gets it
    here. no-store: a credential has no business in a proxy or browser cache."""
    return JSONResponse({"carto_key": settings.carto_key},
                        headers={"Cache-Control": "no-store"})


@router.get("/history")
async def history(hours: int = Query(24, ge=1, le=168),
                  user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    """Read-only per-pack downsampled SOC history for the Fleet Health sparkline."""
    since_ms = int(time.time() * 1000) - hours * 3_600_000
    async with pool.acquire() as conn:
        rows = await q.history_series(conn, since_ms)
    series: dict[str, list[dict]] = {}
    for r in rows:
        series.setdefault(r["address"], []).append({"t": int(r["bucket_ms"]), "soc": float(r["soc"])})
    return {"series": [{"address": a, "points": p} for a, p in series.items()]}


@router.get("/trends")
async def trends(address: Address, from_ms: EpochMs, to_ms: EpochMs,
                 user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    """Read-only adaptive-bucket per-pack SOH / cell-spread / temperature trend series."""
    bucket = q.trend_bucket_ms(max(1, to_ms - from_ms))
    async with pool.acquire() as conn:
        rows = await q.trend_series(conn, address, from_ms, to_ms, bucket)
        first = await q.first_sample_ms(conn, address)
    points = [{"t": int(r["bucket_ms"]),
               "soh": _f(r["soh"]), "cell_spread_mv": _f(r["cell_spread_mv"]),
               "temp_avg": _f(r["temp_avg"]), "temp_min": _f(r["temp_min"]), "temp_max": _f(r["temp_max"])}
              for r in rows]
    return {"address": address, "bucket_ms": bucket,
            "first_ms": int(first) if first is not None else None, "points": points}


@router.get("/charge-sessions")
async def charge_sessions(address: Address, days: int = Query(30, ge=1, le=CHARGE_SESSIONS_MAX_DAYS),
                          user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    """Read-only detected charge sessions (full CC->CV runs) for a pack."""
    since_ms = int(time.time() * 1000) - days * 86_400_000
    async with pool.acquire() as conn:
        buckets = await q.charge_session_buckets(conn, address, since_ms)
    return {"sessions": detect_charge_sessions([
        {"bucket_ms": int(b["bucket_ms"]), "soc": _f(b["soc"]), "temp_max": _f(b["temp_max"])} for b in buckets
    ])}


@router.get("/track")
async def track(address: Address, from_ms: EpochMs, to_ms: EpochMs,
                user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    """Read-only 15-second-bucketed per-pack GPS + discharge series for the Journey map.
    Span-bounded (TRACK_MAX_SPAN_MS); a reversed range is simply empty."""
    if to_ms - from_ms > TRACK_MAX_SPAN_MS:
        raise HTTPException(400, "track range too wide (max 31 days)")
    async with pool.acquire() as conn:
        rows = await q.track_series(conn, address, from_ms, to_ms)
    points = [{"t": int(r["bucket_ms"]), "lat": _f(r["lat"]), "lon": _f(r["lon"]),
               "power_w": _f(r["power_w"]), "current_a": _f(r["current_a"]), "soc": _f(r["soc"]),
               "acc": _f(r["acc"])}
              for r in rows]
    return {"address": address, "points": points}


@router.get("/notes")
async def notes(user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        return {"notes": jsonable(await q.get_notes(conn))}


@router.post("/notes", response_model=OkResponse)
async def post_note(body: NoteBody, user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        await q.upsert_note(conn, body.base_id, body.body, int(time.time() * 1000))
    return OkResponse(ok=True)


@router.get("/samples")
async def samples(address: Address, from_ms: EpochMs, to_ms: EpochMs,
                  user: AuthUser = Depends(require_admin), pool=Depends(get_pool)):
    """Admin-only: full sample history includes GPS coordinates.

    Bounded (SRV-11): the range is clamped to the last 7 days before to_ms and the
    result is hard-capped at SAMPLES_MAX_ROWS rows (see queries.samples_range).

    Serialization of up to SAMPLES_MAX_ROWS full-width rows runs in a thread (the
    single-worker event loop must keep serving ingest/WS/share polls meanwhile);
    the json.dumps kwargs match FastAPI's JSONResponse so the body is unchanged."""
    async with pool.acquire() as conn:
        rows = await q.samples_range(conn, address, from_ms, to_ms)
    payload = await asyncio.get_running_loop().run_in_executor(
        None,
        lambda: json.dumps({"samples": jsonable(rows)}, ensure_ascii=False,
                           allow_nan=False, separators=(",", ":")))
    return Response(payload, media_type="application/json")


@router.get("/devices")
async def devices(user: AuthUser = Depends(require_admin), pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        return {"devices": jsonable(await q.list_devices(conn))}


@router.post("/enroll-codes", response_model=MintCodeResponse)
async def mint_code(user: AuthUser = Depends(require_admin), pool=Depends(get_pool)):
    code = generate_code()
    expires = datetime.now(timezone.utc) + timedelta(minutes=10)
    async with pool.acquire() as conn:
        await q.create_enrollment_code(conn, hash_code(code), user.username, expires)
    return MintCodeResponse(code=code, expires_at=expires.isoformat())


@router.delete("/devices/{device_id}")
async def revoke(device_id: PathUuid, user: AuthUser = Depends(require_admin), pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        await q.revoke_device(conn, device_id)
    return {"revoked": str(device_id)}


@router.post("/devices/{device_id}/restore")
async def restore(device_id: PathUuid, user: AuthUser = Depends(require_admin),
                  pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        found = await q.restore_device(conn, device_id)
    if not found:
        raise HTTPException(404, "device not found")
    # Audit: restoring re-grants the device's existing key its write access.
    logger.info("device restored: %s by %s", device_id, user.username)
    return {"restored": str(device_id)}


_SHARE_DURATION_MS = {"1h": 3_600_000, "1d": 86_400_000, "1w": 7 * 86_400_000}
SHARE_LIST_KEEP_MS = 7 * 86_400_000  # ended shares stay listed for 7 days


@router.post("/shares", response_model=ShareCreateResponse)
async def create_share(body: ShareCreateBody, user: AuthUser = Depends(require_admin),
                       pool=Depends(get_pool)):
    """Mint a public location-share link. Admin-gated: a share grants unauthenticated
    access, same trust class as an enroll code. The token leaves the server only here."""
    token = secrets.token_urlsafe(24)
    now_ms = int(time.time() * 1000)
    expires_at = now_ms + _SHARE_DURATION_MS[body.duration]
    async with pool.acquire() as conn:
        share_id = await q.create_location_share(
            conn, hash_code(token), body.name, user.username, now_ms, expires_at)
    return ShareCreateResponse(id=share_id, name=body.name, expires_at=expires_at,
                               path=f"/share/{token}")


@router.get("/shares")
async def list_shares(user: AuthUser = Depends(require_admin), pool=Depends(get_pool)):
    now_ms = int(time.time() * 1000)
    async with pool.acquire() as conn:
        return {"shares": jsonable(
            await q.list_location_shares(conn, now_ms, SHARE_LIST_KEEP_MS))}


@router.delete("/shares/{share_id}")
async def revoke_share(share_id: int, user: AuthUser = Depends(require_admin),
                       pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        await q.revoke_location_share(conn, share_id, int(time.time() * 1000))
    return {"revoked": share_id}


# ---- read-only API keys for the desktop widgets (app/auth/api_key.py) ----
# Admin-gated for the same reason shares are: a key grants access without a browser
# session, so minting one is the same trust class as minting an enroll code.

@router.post("/api-keys", response_model=ApiKeyCreateResponse)
async def create_api_key(body: ApiKeyCreateBody, user: AuthUser = Depends(require_admin),
                         pool=Depends(get_pool)):
    """Mint a read-only telemetry key. The plaintext leaves the server only here —
    only its sha256 is stored, so a lost key is re-minted rather than recovered."""
    key = secrets.token_urlsafe(32)
    async with pool.acquire() as conn:
        key_id = await q.create_api_key(conn, body.name, hash_api_key(key))
    return ApiKeyCreateResponse(id=key_id, name=body.name, key=key)


@router.get("/api-keys")
async def list_api_keys(user: AuthUser = Depends(require_admin), pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        return {"keys": jsonable(await q.list_api_keys(conn))}


@router.delete("/api-keys/{key_id}")
async def revoke_api_key(key_id: PathUuid, user: AuthUser = Depends(require_admin),
                         pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        ok = await q.revoke_api_key(conn, key_id)
    return {"revoked": str(key_id) if ok else None}
