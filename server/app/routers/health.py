"""GET /api/v1/health/detail: the deadman an external uptime monitor polls (SRV-17/SEC-23).

Distinct from /api/v1/health, which stays a bare DB ping because Docker's healthcheck and
autoheal use it: a phone that stopped uploading must never get the API restarted. This
answers 200 only while telemetry is arriving and the background jobs keep up, else 503
naming the failing checks. Gated by a read-only API key (app/auth/api_key.py): the age of
the last upload says when the chair's phone is offline, which is not for the public."""
import time

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import JSONResponse

from app.auth.api_key import require_api_key
from app.config import settings
from app.db.online_index import online_index_status
from app.db.partitions import next_month_partition_name
from app.db.pool import get_pool
from app.db.rollup import get_high_water_ms
from app.observability import evaluate_health

router = APIRouter(prefix="/api/v1")


@router.get("/health/detail")
async def health_detail(request: Request,
                        max_ingest_age_s: int | None = Query(None, ge=0),
                        _key=Depends(require_api_key), pool=Depends(get_pool)):
    """max_ingest_age_s can only TIGHTEN the ingest limit (it proves the alarm path end to
    end); it can never hide a failure. Ingest age comes from devices.last_seen_at, which
    authenticated uploads refresh at most once a minute."""
    now_ms = int(time.time() * 1000)
    async with pool.acquire() as conn:
        last_seen = await conn.fetchval(
            "SELECT max(last_seen_at) FROM devices WHERE NOT revoked")
        high_water = await get_high_water_ms(conn)
        next_ok = await conn.fetchval("SELECT to_regclass($1) IS NOT NULL",
                                      next_month_partition_name(now_ms))
        indexes = await online_index_status(conn)
    limit = settings.deadman_ingest_s
    if max_ingest_age_s is not None:
        limit = min(limit, max_ingest_age_s)
    stats = request.app.state.auth_stats
    body = evaluate_health(
        now_ms=now_ms,
        last_ingest_ms=None if last_seen is None else int(last_seen.timestamp() * 1000),
        rollup_high_water_ms=high_water, next_month_partition=bool(next_ok),
        auth_fail_5m=stats.failures_in_window(), last_auth_fail=stats.last_failure,
        last_ok_skew_s=stats.last_ok_skew_s, online_indexes=indexes, ingest_limit_s=limit)
    return JSONResponse(body, status_code=200 if body["ok"] else 503,
                        headers={"Cache-Control": "no-store"})
