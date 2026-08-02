"""Public location-share endpoints. The /share/ Traefik zone bypasses Authentik, so
these are reachable by anyone holding a share URL (capability link). Security model:
192-bit tokens (secrets.token_urlsafe(24)), only sha256 stored, unknown/revoked are an
indistinguishable bare 404, expired gets a human page, every response is
no-store/no-referrer, and a per-IP rate limiter throttles scanning. Guests only ever
see today's GPS trail — never battery fields (see queries.gps_track_all)."""

import os
import time
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse

from app.auth.enroll import hash_code
from app.config import settings
from app.db import queries as q
from app.db.pool import get_pool
from app.ratelimit import client_key

router = APIRouter(prefix="/share")

_SEC_HEADERS = {"Referrer-Policy": "no-referrer", "Cache-Control": "no-store"}

_EXPIRED_HTML = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Share expired</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#09090b;color:#f4f4f5;font-family:Inter,system-ui,sans-serif;text-align:center;
padding:24px}p{color:#a1a1aa}</style></head>
<body><div><h2>This location share has expired</h2>
<p>Ask for a new link to keep following along.</p></div></body></html>"""


def share_status(share: dict | None, now_ms: int) -> str:
    """'active' | 'expired' | 'gone'. Unknown and revoked are both 'gone' — deliberately
    indistinguishable to probers; only a genuinely expired (once-valid) link admits it."""
    if share is None or share.get("revoked_at") is not None:
        return "gone"
    return "active" if now_ms < share["expires_at"] else "expired"


def day_window_ms(now: datetime) -> tuple[int, int]:
    """[local midnight, now] in epoch ms — the guest window is always just today,
    regardless of share duration (server-side clamp; guests send no time params).
    Local = the container's TZ env, i.e. the household timezone."""
    local = now.astimezone()
    start = local.replace(hour=0, minute=0, second=0, microsecond=0)
    return int(start.timestamp() * 1000), int(local.timestamp() * 1000)


STATUS_STALE_MS = 120_000  # mirrors the web LIVE_STALE_MS

# The dock must describe the base the guest is following — the chair — and NOT "whichever
# pack polled most recently". The phone polls the staged base every ~1.5 s and rotates
# through the background packs, so whenever the spares are in BLE range (i.e. at home) any
# of the 8 can hold the newest sample: measured against prod, a background pack was the
# freshest ~30% of the time, which flipped the guest dock to an idle spare — 99% CAP, a
# dead FLOW bar — every few polls while the chair sat at 72% and drawing. So resolve the
# active base with the same ladder the Android stage (model/Fleet.kt resolveStage) and the
# WebUI (web/src/stage.ts selectStageItems) already use:
#   1. a base discharging right now = the active chair;
#   2. else the base that discharged most recently within ACTIVE_HOLD_MS — stopped at a
#      crossing, or with one of its packs briefly out of BLE range;
#   3. else the base the dock was already showing (parked; process memory, single worker);
#   4. else the freshest sample — cold start, nothing has moved yet.
# resolveStage's "a charging base may take over" rung is deliberately NOT ported: the
# spares live on chargers, so it would hand the dock straight back to them.
DISCHARGE_EPS = 0.1           # A — same threshold as the app/WebUI (BMS deadband is ~1.04 A)
ACTIVE_HOLD_MS = 15 * 60_000  # mirrors android DEFAULT_STAGE_HOLD_MIN
_DISCHARGE_CACHE_KEY = "recent_discharge"  # fleet-wide, so one key (see TRACK_CACHE_TTL_S)

# Guest polls arrive every ~10 s per guest; the fleet GPS track query is the expensive
# part (~200 ms over today's whole window in prod). Cache it process-wide for one poll
# period so N guests collapse onto <=1 query per TRACK_TTL_S. The cache key is the day
# window's START (local midnight, epoch ms): to_ms is "now" and changes every request,
# but the trail is append-only within a day, so a <=10 s-stale tail is fine — and a new
# day means a new from_ms, so the cache can never serve yesterday's trail past midnight.
TRACK_CACHE_TTL_S = 10.0
# last_access/access_count exist for the owner's Settings list ("last opened", "x N") —
# purely informational. Nothing security-relevant reads them (revocation checks
# revoked_at, expiry checks expires_at), so throttling the touch UPDATE to once per
# share per interval just makes access_count approximate (undercounts rapid polls)
# while cutting a dead tuple per guest poll.
TOUCH_INTERVAL_S = 60.0


def _f(v):
    return float(v) if v is not None else None


def parse_since(raw: str | None, from_ms: int) -> int | None:
    """The guest's newest known bucket start, or None = "send the whole day".

    Garbage, empty, or a value from before today's window all degrade to None rather than
    erroring: a share link must never 4xx on a stray query param, and the full trail is
    always a correct answer — just a bigger one. Taken as a string (not an int path param)
    precisely so FastAPI can't turn `?since=banana` into a 422 for a guest."""
    if raw is None:
        return None
    try:
        value = int(raw)
    except (TypeError, ValueError):
        return None
    return value if value > from_ms else None


def _pack_label(row: dict) -> str:
    """'2012 · A' -> 'A'; fall back to the address tail for unaliased packs."""
    alias = (row.get("alias") or "")
    if "·" in alias:
        tail = alias.split("·")[-1].strip()
        if tail:
            return tail
    return (row.get("address") or "??")[-2:]


def _group_key(row: dict):
    """Base identity. Falls back to the address so two ungrouped packs (group_id NULL,
    e.g. a battery not yet aliased) never merge into one false "base" with summed flow."""
    return row.get("group_id") or row.get("address")


def resolve_active_group(rows: list[dict], fresh: list[dict],
                         last_discharge_ms: dict[str, int] | None,
                         sticky: str | None, now_ms: int):
    """Which base the guest dock follows — the four-rung ladder documented above.
    [fresh] is the non-stale subset of [rows]; [last_discharge_ms] is address -> ts of
    the last discharging sample (queries.recent_discharge_by_address)."""
    live = {_group_key(r) for r in fresh}
    # 1. drawing right now. Deepest draw wins: the server has no daily-driver notion to
    #    break ties with, and the base under real load is the one being ridden.
    drawing = [r for r in fresh if float(r.get("current_a") or 0.0) < -DISCHARGE_EPS]
    if drawing:
        return _group_key(min(drawing, key=lambda r: float(r["current_a"])))
    # 2. recent-discharge hold. Keyed off the BASE, not the pack, and resolved against
    #    every row (not just fresh ones) so the pack that was drawing can drop out of BLE
    #    range while its sibling keeps the dock on the right base.
    group_of = {r["address"]: _group_key(r) for r in rows}
    held = [(ts, g) for addr, ts in (last_discharge_ms or {}).items()
            if (g := group_of.get(addr)) in live and now_ms - ts < ACTIVE_HOLD_MS]
    if held:
        return max(held)[1]
    # 3. parked: stay where we were, as long as that base is still reporting.
    if sticky in live:
        return sticky
    # 4. nothing has moved since the process started.
    return _group_key(max(fresh, key=lambda r: r["ts_ms"]))


def pick_guest_status(rows: list[dict], now_ms: int,
                      last_discharge_ms: dict[str, int] | None = None,
                      sticky: str | None = None) -> tuple[dict | None, str | None]:
    """Aggregate the active base's latest telemetry into the guest dock payload.

    Returns (payload, active base) — the caller feeds the base back in as [sticky] on the
    next poll (rung 3). Rows older than STATUS_STALE_MS are ignored; no fresh rows ->
    (None, sticky): the dock renders empty but a momentary fleet blackout must not lose
    the base we were following. Deliberate, minimal battery surface (2026-07-14 spec
    amendment): only soc/current/power/regen ever leave the server here — never voltage,
    temperature, cells, or cycles."""
    fresh = [r for r in rows
             if r.get("ts_ms") and r["ts_ms"] >= now_ms - STATUS_STALE_MS
             and r.get("soc") is not None]
    if not fresh:
        return None, sticky
    active = resolve_active_group(rows, fresh, last_discharge_ms, sticky, now_ms)
    group = [r for r in fresh if _group_key(r) == active]
    packs = sorted(
        ({"label": _pack_label(r), "soc": int(round(float(r["soc"])))} for r in group),
        key=lambda p: p["label"])
    return {
        "ts": max(int(r["ts_ms"]) for r in group),
        "soc": min(p["soc"] for p in packs),
        "packs": packs,
        "current_a": round(sum(float(r.get("current_a") or 0.0) for r in group), 2),
        "power_w": round(sum(float(r.get("power_w") or 0.0) for r in group), 1),
        "regen": any(bool(r.get("regen")) for r in group),
    }, active


async def _resolve(request: Request, token: str, pool) -> tuple[str, dict | None]:
    key = client_key(request.client.host if request.client else None, request.headers)
    if not request.app.state.share_limiter.allow(key):
        raise HTTPException(429, "too many requests", headers=_SEC_HEADERS)
    async with pool.acquire() as conn:
        share = await q.get_location_share(conn, hash_code(token))
    return share_status(share, int(time.time() * 1000)), share


@router.get("/{token}")
async def share_page(token: str, request: Request, pool=Depends(get_pool)):
    status, _share = await _resolve(request, token, pool)
    if status == "gone":
        raise HTTPException(404, "Not Found", headers=_SEC_HEADERS)
    if status == "expired":
        return HTMLResponse(_EXPIRED_HTML, headers=_SEC_HEADERS)
    index = os.path.join(os.environ.get("BMSMON_WEB_DIST", "/app/web/dist"),
                         "share", "index.html")
    return FileResponse(index, headers=_SEC_HEADERS)


@router.get("/{token}/feed")
async def share_feed(token: str, request: Request, since: str | None = None,
                     pool=Depends(get_pool)):
    status, share = await _resolve(request, token, pool)
    if status == "gone":
        raise HTTPException(404, "Not Found", headers=_SEC_HEADERS)
    if status == "expired":
        raise HTTPException(410, "share expired", headers=_SEC_HEADERS)
    from_ms, now_ms = day_window_ms(datetime.now(timezone.utc))
    state = request.app.state
    # Per-share state above (expiry/410/revocation) and the guest *status* below stay
    # per-request; only the fleet-wide GPS trail — identical for every guest — is cached.
    points = state.share_track_cache.get(from_ms)
    # Fleet-wide like the trail, so it is cached on the same period: N guests collapse
    # onto <=1 discharge lookup per TTL, and a <=10 s-stale answer is nothing against a
    # 15-minute hold window.
    last_discharge = state.share_discharge_cache.get(_DISCHARGE_CACHE_KEY)
    async with pool.acquire() as conn:
        if points is None:
            rows = await q.gps_track_all(conn, from_ms, now_ms + 1)
            points = [{"t": int(r["bucket_ms"]), "lat": r["lat"], "lon": r["lon"],
                       "power_w": _f(r["power_w"]), "current_a": _f(r["current_a"])}
                      for r in rows]
            state.share_track_cache.put(from_ms, points)
        if last_discharge is None:
            last_discharge = await q.recent_discharge_by_address(
                conn, now_ms - ACTIVE_HOLD_MS, DISCHARGE_EPS)
            state.share_discharge_cache.put(_DISCHARGE_CACHE_KEY, last_discharge)
        snapshot = await q.fleet_snapshot(conn)
        if state.share_touch.should_touch(share["id"]):
            await q.touch_location_share(conn, share["id"], now_ms)
    status, active = pick_guest_status(snapshot, now_ms, last_discharge,
                                       state.share_active_base)
    state.share_active_base = active
    # Incremental poll: slice the CACHED list — no extra query, so the DB cost of a poll
    # is flat no matter how fast guests poll. A 15 s GPS bucket can't be fresher than the
    # cache TTL anyway, so there is nothing to gain from re-querying. `since` is the
    # client's newest bucket START, so that partially-filled bucket is re-sent and
    # REPLACED client-side (appendTrack's seam rule) — its averages are still moving.
    cut = parse_since(since, from_ms)
    trail = points if cut is None else [p for p in points if p["t"] >= cut]
    return JSONResponse(
        # `last` comes from the FULL trail, never the slice: a poll with no new buckets
        # must not blank the guest's live chair marker.
        {"points": trail, "last": points[-1] if points else None,
         "expires_at": share["expires_at"], "now": now_ms, "day_start": from_ms,
         "owner": settings.share_owner, "status": status},
        headers=_SEC_HEADERS)
