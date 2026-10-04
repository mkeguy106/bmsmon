import base64
import binascii
import logging
import uuid
import zlib
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from app.auth.device_jwt import JwtError, skew_of, unverified_sub, verify_body, verify_token
from app.auth.enroll import hash_code
from app.config import settings
from app.db import queries as q
from app.db.pool import get_pool
from app.models import (
    ConfigResponse, EnrollBody, EnrollResponse, IngestEnvelope, IngestResponse,
    RangeConfigRow, SampleIn, TempConfigBody, first_error, validate_each,
)
from app.observability import clean_user_agent
from app.ratelimit import client_key

router = APIRouter(prefix="/api/v1")

logger = logging.getLogger(__name__)


def _partition_ts_window() -> tuple[int, int]:
    """Sane ts_ms window for ingested samples (see Settings.ingest_ts_min_ms)."""
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    return settings.ingest_ts_min_ms, now_ms + settings.ingest_ts_max_future_ms


def _gps_cutoff_ms() -> int | None:
    """Samples older than this are past GPS retention (SEC-12); None when retention is off."""
    days = settings.gps_retention_days
    if days <= 0:
        return None
    return int(datetime.now(timezone.utc).timestamp() * 1000) - days * 86_400_000


# SRV-13: max accepted sample-address length. BLE MACs are 17 chars; the headroom is
# forward compat (e.g. iOS CoreBluetooth surfaces UUID-ish identifiers, not MACs).
ADDRESS_MAX_LEN = 32

# C3: invalid-item WARNINGs at most once per device per kind per interval. An outbox
# drain of a drifted client would otherwise log the same failure for every batch.
REJECT_LOG_INTERVAL_S = 60.0

# DATA-20: the machine-readable cause of a device-route 401. Values: missing_bearer,
# bad_token, unknown_or_revoked_device, bad_signature, clock_skew, replay, body_mismatch.
# Only clock_skew means "the clocks disagree"; the body's detail text is unchanged.
AUTH_REASON_HEADER = "X-Bmsmon-Auth-Reason"


def _may_log_reject(request: Request, device_id: str, kind: str) -> bool:
    """True at most once per device per `kind` per REJECT_LOG_INTERVAL_S (app.state.reject_log)."""
    return request.app.state.reject_log.should_touch((device_id, kind))


def _deny(reason: str, detail: str) -> HTTPException:
    return HTTPException(401, detail, headers={AUTH_REASON_HEADER: reason})


def _note_auth_failure(request: Request, device_id: str, reason: str, message: str,
                       skew_s: int | None) -> None:
    """A KNOWN device failed auth: count it for /api/v1/health/detail and log it with the
    skew and the app build, at most once per device per reason per REJECT_LOG_INTERVAL_S."""
    request.app.state.auth_stats.record_failure(reason, skew_s)
    if _may_log_reject(request, device_id, f"auth:{reason}"):
        logger.warning(
            "device auth failed for %s: %s (%s); skew %s; ua %r "
            "(repeats of this reason for this device suppressed for %.0f s)",
            device_id, reason, message, "n/a" if skew_s is None else f"{skew_s:+d} s",
            clean_user_agent(request.headers.get("user-agent")), REJECT_LOG_INTERVAL_S)


def _log_envelope_reject(request: Request, device_id: str, err: ValidationError) -> None:
    if _may_log_reject(request, device_id, "envelope"):
        logger.warning("%s: refused an invalid envelope from device %s with 422: %s "
                       "(repeats for this device suppressed for %.0f s)",
                       request.url.path, device_id, first_error(err), REJECT_LOG_INTERVAL_S)


def _log_rejects(request: Request, device_id: str, kind: str,
                 rejects: list[tuple[int, str]], total: int) -> None:
    """WARNING for dropped-invalid items: count + the first error's location and type."""
    if not _may_log_reject(request, device_id, kind):
        return
    index, reason = rejects[0]
    logger.warning(
        "%s: dropped %d/%d invalid %s(s) from device %s; first: #%d %s "
        "(repeats for this device suppressed for %.0f s)",
        request.url.path, len(rejects), total, kind, device_id, index, reason,
        REJECT_LOG_INTERVAL_S)


def _address_ok(address: str) -> bool:
    """SRV-13 sample-address sanity rule: every ingested address becomes a PERMANENT
    `batteries` registry row, so junk must not get through. The rule (deliberately one
    simple documented predicate, not a strict MAC regex): non-empty, at most
    ADDRESS_MAX_LEN chars, printable non-space ASCII only (0x21-0x7E). Real BLE MACs
    (`C8:47:80:15:25:01`, any case) trivially pass; empty strings, whitespace,
    control bytes, non-ASCII garbage and oversized blobs are dropped — never 4xx'd,
    because the phone poison-skips 4xx batches (same policy as the ts_ms filter).
    """
    return (0 < len(address) <= ADDRESS_MAX_LEN
            and all(0x21 <= ord(c) <= 0x7E for c in address))


def _gunzip_capped(data: bytes, limit: int) -> bytes:
    """Incrementally gunzip with a hard decompressed-size ceiling (anti gzip-bomb).

    413 if the plaintext would exceed `limit`; 400 on corrupt/truncated gzip
    (matching the old gzip.decompress behavior).
    """
    d = zlib.decompressobj(wbits=31)  # 31 = gzip container
    try:
        out = d.decompress(data, limit + 1)
    except zlib.error:
        raise HTTPException(400, "bad gzip")
    if len(out) > limit:
        raise HTTPException(413, "decompressed body too large")
    if not d.eof:
        raise HTTPException(400, "bad gzip")
    return out


async def _read_body(request: Request) -> bytes:
    """Read the request body with size caps. Called only AFTER _authenticate (SEC-18).

    Rejects declared Content-Length > max_body_bytes with 413 without reading;
    also enforces the cap while streaming (absent/lying Content-Length). If the
    body is gzipped, decompresses with a hard ceiling (see _gunzip_capped) —
    the device JWT's body hash is over the plaintext JSON, so callers get the
    decompressed bytes.
    """
    max_body = settings.max_body_bytes
    declared = request.headers.get("content-length")
    if declared is not None:
        try:
            if int(declared) > max_body:
                raise HTTPException(413, "body too large")
        except ValueError:
            pass  # malformed header; the streaming cap below still protects us
    chunks: list[bytes] = []
    total = 0
    async for chunk in request.stream():
        total += len(chunk)
        if total > max_body:
            raise HTTPException(413, "body too large")
        chunks.append(chunk)
    raw = b"".join(chunks)
    if request.headers.get("content-encoding", "").lower() == "gzip":
        raw = _gunzip_capped(raw, settings.max_gunzip_bytes)
    return raw


async def _authenticate(request: Request, pool) -> tuple[str, dict]:
    """SEC-18 stage 1: authenticate a device request WITHOUT touching its body.

    Bearer -> device row -> ES256 signature + required claims + exp/iat/aud
    (device_jwt.verify_token) -> replay probe -> per-device budget. Only a request that
    passes all of it ever has its body read and gunzipped (_read_verified_body). Never
    burns the jti; see device_jwt.verify_body. Returns (device_id, claims). Every 401 names
    its cause in X-Bmsmon-Auth-Reason; failures of a KNOWN device are counted and logged.
    """
    auth = request.headers.get("authorization", "")
    if not auth.lower().startswith("bearer "):
        raise _deny("missing_bearer", "missing bearer")
    token = auth[7:]
    try:
        # Canonicalise once: uuid.UUID() also accepts {braced}, urn:uuid:, uppercase and
        # unhyphenated spellings. asyncpg rejects the first two (a pre-auth 500), and the
        # rest would each get their own budget bucket. Everything below uses this form.
        device_id = str(uuid.UUID(unverified_sub(token)))
    except (JwtError, ValueError, TypeError, AttributeError):
        # AttributeError: a non-string sub (e.g. 123) used to escape as a pre-auth 500.
        raise _deny("bad_token", "bad token")
    async with pool.acquire() as conn:
        dev = await q.get_device(conn, device_id)
    if dev is None:
        # Anyone can mint a well-formed token for a random id: one line a minute, uncounted.
        if _may_log_reject(request, "*", "auth:unknown_device"):
            logger.warning("device auth failed: unknown device %s (further unknown-device "
                           "failures suppressed for %.0f s)", device_id, REJECT_LOG_INTERVAL_S)
        raise _deny("unknown_or_revoked_device", "unknown or revoked device")
    if dev["revoked"]:
        _note_auth_failure(request, device_id, "unknown_or_revoked_device", "device is revoked", None)
        raise _deny("unknown_or_revoked_device", "unknown or revoked device")
    try:
        claims = verify_token(token, bytes(dev["public_key_spki"]))
    except JwtError as e:
        _note_auth_failure(request, device_id, e.reason, str(e), e.skew_s)
        raise _deny(e.reason, "bad signature")
    if request.app.state.jti_cache.contains(claims["jti"]):
        # replay, refused before the body is read
        _note_auth_failure(request, device_id, "replay", "jti already used", None)
        raise _deny("replay", "bad signature")
    if not request.app.state.ingest_limiter.allow(device_id):
        # Throttled like the drop WARNINGs, so a retry storm can't flood the log.
        if _may_log_reject(request, device_id, "budget"):
            logger.warning("device upload budget exceeded for %s "
                           "(repeats for this device suppressed for %.0f s)",
                           device_id, REJECT_LOG_INTERVAL_S)
        raise HTTPException(429, "too many requests; slow down")
    return device_id, claims


async def _read_verified_body(request: Request, device_id: str, claims: dict) -> bytes:
    """SEC-18 stage 2: read + gunzip the capped body, bind it to the token (bh), and only
    then burn the jti. Returns the decompressed plaintext. A fully verified request
    records its clock skew (server_now - iat) for /api/v1/health/detail."""
    raw = await _read_body(request)
    try:
        verify_body(claims, raw, request.app.state.jti_cache)
    except JwtError as e:
        _note_auth_failure(request, device_id, e.reason, str(e), e.skew_s)
        raise _deny(e.reason, "bad signature")
    request.app.state.auth_stats.record_ok(skew_of(claims))
    return raw


@router.get("/health")
async def health(pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        await conn.execute("SELECT 1")
    return JSONResponse({"status": "ok"})


@router.post("/enroll", response_model=EnrollResponse)
async def enroll(body: EnrollBody, request: Request, pool=Depends(get_pool)):
    # SEC-4: /enroll is the only unauthenticated (code-gated) endpoint — per-IP
    # rate limit before doing any work. Key resolution + process-local caveats
    # are documented in app/ratelimit.py.
    key = client_key(request.client.host if request.client else None, request.headers)
    if not request.app.state.enroll_limiter.allow(key):
        logger.warning("enroll: rate limit exceeded for %s", key)
        raise HTTPException(429, "too many enrollment attempts; try again later")
    try:
        spki = base64.b64decode(body.public_key_spki_b64, validate=True)
    except (binascii.Error, ValueError):
        raise HTTPException(400, "bad public key")
    if not spki:
        raise HTTPException(400, "bad public key")
    now = datetime.now(timezone.utc)
    async with pool.acquire() as conn:
        async with conn.transaction():
            device_id = await q.create_device(conn, body.install_uuid, spki, body.device_label)
            if device_id is None:
                # Revoked install_uuid (T2.4/SRV-7): refuse enrollment BEFORE claiming the
                # code, so the code is not burned and stays usable for another device. The
                # upsert was a no-op (WHERE revoked=false), so key/revoked are untouched.
                raise HTTPException(403, "device revoked; restore it first")
            claimed = await q.claim_code(conn, hash_code(body.code), device_id, now)
            if claimed is None:
                raise HTTPException(400, "invalid or expired code")
    return EnrollResponse(device_id=str(device_id))


async def _touch(conn, request: Request, device_id: str, *, seen: bool) -> None:
    """Device bookkeeping: the app build that sent the request (DATA-28) and, with
    seen=True, devices.last_seen_at. last_seen_at is the deadman's ingest signal
    (/api/v1/health/detail) and the device list's "last upload", so only a live ingest
    batch that stored samples passes seen=True. Pure bookkeeping: any error is logged and
    swallowed, and the savepoint keeps a failure from aborting a caller's transaction."""
    user_agent = clean_user_agent(request.headers.get("user-agent"))
    if not seen and user_agent is None:
        return  # nothing to record
    try:
        async with conn.transaction():
            if seen:
                await q.touch_device(conn, device_id, user_agent)
            else:
                await q.record_user_agent(conn, device_id, user_agent)
    except Exception:
        logger.warning("device bookkeeping failed for %s", device_id, exc_info=True)


@router.post("/ingest", response_model=IngestResponse)
async def ingest(request: Request, pool=Depends(get_pool)):
    device_id, claims = await _authenticate(request, pool)
    # The device may gzip the body; it is read (capped) and decompressed only now, after
    # the token verified. The JWT's bh is over the decompressed plaintext.
    raw = await _read_verified_body(request, device_id, claims)
    # C3/SRV-18: ONLY the envelope can 422. Each sample is validated on its own and
    # dropped if invalid; the phone deletes a 4xx'd batch, so one bad field must never
    # cost the other rows.
    try:
        env = IngestEnvelope.model_validate_json(raw)
    except ValidationError as e:
        _log_envelope_reject(request, device_id, e)
        raise HTTPException(422, "invalid body")
    parsed, rejects = validate_each(SampleIn, env.samples)
    if rejects:
        _log_rejects(request, device_id, "sample", rejects, len(env.samples))
    # T2.3/SRV-5: drop (don't 4xx) samples whose device-supplied ts_ms is outside a
    # sane window — ts_ms drives partition CREATE TABLEs and datetime conversion.
    ts_min, ts_max = _partition_ts_window()
    samples = [s for s in parsed if ts_min <= s.ts_ms <= ts_max]
    if len(samples) != len(parsed) and _may_log_reject(request, device_id, "ts_ms window"):
        bad = [s.ts_ms for s in parsed if not (ts_min <= s.ts_ms <= ts_max)]
        logger.warning(
            "ingest: dropped %d/%d sample(s) with out-of-range ts_ms from device %s: %s "
            "(repeats for this device suppressed for %.0f s)",
            len(bad), len(parsed), device_id, bad[:10], REJECT_LOG_INTERVAL_S)
    # SRV-13: same drop-don't-4xx policy for junk addresses, which would otherwise
    # create permanent `batteries` registry rows (rule documented on _address_ok).
    kept = [s for s in samples if _address_ok(s.address)]
    if len(kept) != len(samples) and _may_log_reject(request, device_id, "address"):
        bad_addr = [s.address for s in samples if not _address_ok(s.address)]
        logger.warning(
            "ingest: dropped %d/%d sample(s) with invalid address from device %s: %s "
            "(repeats for this device suppressed for %.0f s)",
            len(bad_addr), len(samples), device_id,
            [a[:40].encode("ascii", "backslashreplace").decode() for a in bad_addr[:10]],
            REJECT_LOG_INTERVAL_S)
    samples = kept
    dropped = len(env.samples) - len(samples)
    # model_dump() once per sample, reused for both the DB row and the WS publish
    # below (it used to run twice per sample on the ingest hot path). sample_row
    # and publish both only READ the dict, so sharing it is safe.
    dumped = [s.model_dump() for s in samples]
    # SEC-12/SRV-22: a sample already past GPS retention when it arrives (a late outbox
    # drain, a history import) is stored and broadcast without coordinates: the daily scrub
    # walks forward from its watermark and would never revisit it.
    gps_cutoff = _gps_cutoff_ms()
    if gps_cutoff is not None:
        for d in dumped:
            if d["ts_ms"] < gps_cutoff:
                d["lat"] = d["lon"] = d["gps_accuracy_m"] = None
    rows = [q.sample_row(device_id, s.address, d) for s, d in zip(samples, dumped)]
    # SRV-13: one registry upsert per unique address per batch (not per sample).
    # Dict insertion order keeps the LAST-seen sample's alias/group per address.
    by_addr = {s.address: s for s in samples}
    async with pool.acquire() as conn:
        async with conn.transaction():
            for s in by_addr.values():
                await q.upsert_battery(conn, s.address, s.advertised_name, s.alias,
                                       s.group_id, s.ts_ms)
            accepted = await q.insert_samples(conn, rows)
        # devices.last_seen_at is the deadman's ingest signal (/api/v1/health/detail) and
        # the device list's "last upload". Only a LIVE batch (batch_seq >= 0) that got at
        # least one valid sample to the insert refreshes it: an all-dropped batch is a
        # pipeline losing data, and an import is old history. A batch of duplicates still
        # counts (accepted == 0, but a healthy re-send). Throttled to once per device per
        # minute: batches land every ~15 s and each UPDATE is a dead tuple. The throttle is
        # consulted only for a qualifying batch, so a dropped one can't use up the window.
        if (env.batch_seq >= 0 and rows
                and request.app.state.device_touch.should_touch(device_id)):
            await _touch(conn, request, device_id, seen=True)
    # batch_seq < 0 (-1) marks a historical-import batch (see IngestEnvelope): store it,
    # but don't flood the live WS dashboards with thousands of stale frames (WEB-5).
    if env.batch_seq >= 0:
        for d in dumped:
            await request.app.state.bus.publish({"type": "sample", **d})
    return IngestResponse(accepted=accepted, dropped=dropped, last_seq=env.batch_seq)


@router.post("/config", response_model=ConfigResponse)
async def config(request: Request, pool=Depends(get_pool)):
    """One-way temperature-alert config push from the phone (same auth/gzip/verify as
    ingest). The core thresholds are the envelope (422 if malformed); ranges[] rows are
    validated one by one and invalid rows dropped (C3)."""
    device_id, claims = await _authenticate(request, pool)
    raw = await _read_verified_body(request, device_id, claims)
    try:
        cfg = TempConfigBody.model_validate_json(raw)
    except ValidationError as e:
        _log_envelope_reject(request, device_id, e)
        raise HTTPException(422, "invalid body")
    ranges, rejects = validate_each(RangeConfigRow, cfg.ranges or [])
    if rejects:
        _log_rejects(request, device_id, "range row", rejects, len(cfg.ranges or []))
    async with pool.acquire() as conn:
        await q.upsert_temp_config(conn, device_id, cfg.model_dump())
        # Device-level capacity alert sync (parallel to temp config): only when the phone
        # includes it — a temp-only body leaves seize_soc None and the alert config untouched.
        if cfg.seize_soc is not None:
            await q.upsert_alert_config(
                conn, device_id, cfg.seize_soc,
                cfg.alerts_on if cfg.alerts_on is not None else True, cfg.updated_at_ms)
        # Learned discharge-range bands: only the rows that validated; a temp-only body
        # (ranges None) leaves the stored rows untouched.
        for row in ranges:
            await q.upsert_range_config(conn, device_id, row.model_dump())
        # The app build only: a config push is not telemetry, so it never moves the
        # deadman's last_seen_at.
        await _touch(conn, request, device_id, seen=False)
    return ConfigResponse(dropped=len(rejects))
