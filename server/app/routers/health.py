"""GET /api/v1/health/detail: the deadman an external uptime monitor polls (SRV-17/SEC-23).

Distinct from /api/v1/health, which stays a bare DB ping because Docker's healthcheck and
autoheal use it: a phone that stopped uploading must never get the API restarted. This
answers 200 only while telemetry is arriving and the background jobs keep up, else 503
naming the failing checks. "Telemetry is arriving" means samples are being STORED: the
ingest check reads devices.last_seen_at, which only a live ingest batch that keeps at least
one valid sample refreshes (routers/api_device.py), never a batch whose every sample was
dropped, a history import or a config push. Gated by a read-only API key
(app/auth/api_key.py): the age of the last upload says when the chair's phone is offline,
which is not for the public."""
import time
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import JSONResponse

from app.auth.api_key import require_api_key
from app.config import settings
from app.db.online_index import online_index_status
from app.db.partitions import next_month_partition_name
from app.db.pool import get_pool
from app.db.rollup import get_high_water_ms
from app.observability import (DEFAULT_HEALTH_CHECKS, HEALTH_CHECKS, PHONE_FAULT_CONFIRM_S,
                               evaluate_health)

router = APIRouter(prefix="/api/v1")

# One round trip: the newest non-revoked phone status, plus whether ANY non-revoked device
# has a reported fault that has persisted long enough (a NULL fault_since counts from the
# status time). Staleness never clears a fault: a silent phone keeps its last verdict. $1 = now as a timestamptz, so the SQL and the body share one clock.
_PHONE_SQL = """
SELECT d.phone_level, d.phone_plugged, d.phone_fault, d.phone_fault_since, d.phone_status_at,
       EXISTS (SELECT 1 FROM devices f
               WHERE NOT f.revoked AND f.phone_fault
                 AND coalesce(f.phone_fault_since, f.phone_status_at)
                     <= $1::timestamptz - make_interval(secs => $2::float8)) AS confirmed
FROM devices d
WHERE NOT d.revoked AND d.phone_status_at IS NOT NULL
ORDER BY d.phone_status_at DESC
LIMIT 1
"""


def _ms(ts) -> int | None:
    return None if ts is None else int(ts.timestamp() * 1000)


@router.get("/health/detail")
async def health_detail(request: Request,
                        max_ingest_age_s: int | None = Query(None, ge=0),
                        checks: str | None = Query(None),
                        _key=Depends(require_api_key), pool=Depends(get_pool)):
    """max_ingest_age_s can only TIGHTEN the ingest limit (it proves the alarm path end to
    end); it can never hide a failure. Ingest age comes from devices.last_seen_at, which
    only a live upload that stores at least one valid sample refreshes, at most once a
    minute. `checks` (comma-separated subset of ingest, rollup, partition, clock, phone_power, phone_battery)
    selects what can fail the response; the default is the first four, so the deadman monitor
    is unaffected by phone_power."""
    selected = DEFAULT_HEALTH_CHECKS
    if checks is not None:
        names = tuple(dict.fromkeys(c.strip() for c in checks.split(",")))
        if not names or any(n not in HEALTH_CHECKS for n in names):
            raise HTTPException(422, "unknown check; allowed: " + ",".join(HEALTH_CHECKS))
        selected = names
    now_ms = int(time.time() * 1000)
    async with pool.acquire() as conn:
        last_seen = await conn.fetchval(
            "SELECT max(last_seen_at) FROM devices WHERE NOT revoked")
        high_water = await get_high_water_ms(conn)
        next_ok = await conn.fetchval("SELECT to_regclass($1) IS NOT NULL",
                                      next_month_partition_name(now_ms))
        indexes = await online_index_status(conn)
        now_ts = datetime.fromtimestamp(now_ms / 1000, tz=timezone.utc)
        row = await conn.fetchrow(_PHONE_SQL, now_ts, PHONE_FAULT_CONFIRM_S)
    phone = None if row is None else {
        "level": row["phone_level"], "plugged": row["phone_plugged"],
        "fault": bool(row["phone_fault"]), "fault_since_ms": _ms(row["phone_fault_since"]),
        "status_ms": _ms(row["phone_status_at"]), "fault_confirmed": row["confirmed"]}
    limit = settings.deadman_ingest_s
    if max_ingest_age_s is not None:
        limit = min(limit, max_ingest_age_s)
    stats = request.app.state.auth_stats
    body = evaluate_health(
        now_ms=now_ms,
        last_ingest_ms=None if last_seen is None else int(last_seen.timestamp() * 1000),
        rollup_high_water_ms=high_water, next_month_partition=bool(next_ok),
        auth_fail_5m=stats.failures_in_window(), last_auth_fail=stats.last_failure,
        last_ok_skew_s=stats.last_ok_skew_s, online_indexes=indexes, ingest_limit_s=limit,
        checks=selected, phone=phone, phone_low_pct=settings.phone_low_pct)
    return JSONResponse(body, status_code=200 if body["ok"] else 503,
                        headers={"Cache-Control": "no-store"})
