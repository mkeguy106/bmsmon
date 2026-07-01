import base64
import binascii
import uuid
import zlib
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from app.auth.device_jwt import JwtError, unverified_sub, verify
from app.auth.enroll import hash_code
from app.config import settings
from app.db import queries as q
from app.db.pool import get_pool
from app.models import (
    EnrollBody, EnrollResponse, IngestBody, IngestResponse, OkResponse, TempConfigBody,
)

router = APIRouter(prefix="/api/v1")


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
    """Read the request body with size caps, BEFORE any signature verification.

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


@router.get("/health")
async def health(pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        await conn.execute("SELECT 1")
    return JSONResponse({"status": "ok"})


@router.post("/enroll", response_model=EnrollResponse)
async def enroll(body: EnrollBody, pool=Depends(get_pool)):
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
            claimed = await q.claim_code(conn, hash_code(body.code), device_id, now)
            if claimed is None:
                raise HTTPException(400, "invalid or expired code")
    return EnrollResponse(device_id=str(device_id))


@router.post("/ingest", response_model=IngestResponse)
async def ingest(request: Request, pool=Depends(get_pool)):
    auth = request.headers.get("authorization", "")
    if not auth.lower().startswith("bearer "):
        raise HTTPException(401, "missing bearer")
    token = auth[7:]
    # The device may gzip the body to save bandwidth; _read_body caps the wire and
    # decompressed sizes and returns the plaintext the JWT's body hash is over.
    raw = await _read_body(request)
    try:
        device_id = unverified_sub(token)
    except JwtError:
        raise HTTPException(401, "bad token")
    try:
        uuid.UUID(device_id)
    except (ValueError, TypeError):
        raise HTTPException(401, "bad token")
    async with pool.acquire() as conn:
        dev = await q.get_device(conn, device_id)
        if dev is None or dev["revoked"]:
            raise HTTPException(401, "unknown or revoked device")
        try:
            verify(token, bytes(dev["public_key_spki"]), raw, request.app.state.jti_cache)
        except JwtError:
            raise HTTPException(401, "bad signature")
        try:
            body = IngestBody.model_validate_json(raw)
        except ValidationError:
            raise HTTPException(422, "invalid body")
        rows = [q.sample_row(device_id, s.address, s.model_dump()) for s in body.samples]
        async with conn.transaction():
            for s in body.samples:
                await q.upsert_battery(conn, s.address, s.advertised_name, s.alias,
                                       s.group_id, s.ts_ms)
            accepted = await q.insert_samples(conn, rows)
        await conn.execute("UPDATE devices SET last_seen_at=now() WHERE id=$1", device_id)
    for s in body.samples:
        await request.app.state.bus.publish({"type": "sample", **s.model_dump()})
    return IngestResponse(accepted=accepted, last_seq=body.batch_seq)


@router.post("/config", response_model=OkResponse)
async def config(request: Request, pool=Depends(get_pool)):
    """One-way temperature-alert config push from the phone (same auth/gzip/verify as ingest)."""
    auth = request.headers.get("authorization", "")
    if not auth.lower().startswith("bearer "):
        raise HTTPException(401, "missing bearer")
    token = auth[7:]
    raw = await _read_body(request)
    try:
        device_id = unverified_sub(token)
        uuid.UUID(device_id)
    except (JwtError, ValueError, TypeError):
        raise HTTPException(401, "bad token")
    async with pool.acquire() as conn:
        dev = await q.get_device(conn, device_id)
        if dev is None or dev["revoked"]:
            raise HTTPException(401, "unknown or revoked device")
        try:
            verify(token, bytes(dev["public_key_spki"]), raw, request.app.state.jti_cache)
        except JwtError:
            raise HTTPException(401, "bad signature")
        try:
            cfg = TempConfigBody.model_validate_json(raw)
        except ValidationError:
            raise HTTPException(422, "invalid body")
        await q.upsert_temp_config(conn, device_id, cfg.model_dump())
        await conn.execute("UPDATE devices SET last_seen_at=now() WHERE id=$1", device_id)
    return OkResponse()
