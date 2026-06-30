# bmsmon Telemetry Cloud — Phase 1 (Server + WebUI) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the `bmsmon.covert.life` API server (FastAPI + Postgres) and a React live dashboard, demonstrable end-to-end with a fake telemetry feeder — before any phone or NAS work.

**Architecture:** One FastAPI process exposes a device zone under `/api/` (no Authentik; ES256-JWT device auth) and a browser zone everywhere else (Authentik forwardAuth headers). Samples land in a partitioned Postgres `samples` table and are fanned out live to WebSocket clients via an in-process bus. The React build is served as static assets by the same app.

**Tech Stack:** Python 3.12, FastAPI, uvicorn, asyncpg (raw SQL, no ORM), PyJWT + `cryptography` (ES256), pytest + httpx; React 18 + TypeScript + Vite + Vitest. Postgres 16.

## Global Constraints

- Python **3.12**; Postgres **16**.
- Server is **strictly read-only** toward batteries — there are no endpoints that command a BMS; the server only ingests and displays.
- Device endpoints live **only** under `/api/` (they will bypass Authentik in prod via Traefik). Everything else is browser zone.
- Sample row schema **mirrors the phone's `SampleEntity`** field-for-field (names may be snake_cased): `state, soc, current_a, power_w, voltage_v, temp_c, mosfet_temp_c, soh, full_charge_ah, remaining_ah, cycles, cell_min_v, cell_max_v, regen, link_event`, plus `device_id, address, ts_ms`.
- Ingest is **idempotent**: unique key `(device_id, address, ts_ms)`, `ON CONFLICT DO NOTHING`.
- Device auth: **ES256 JWT** signed by the device's P-256 key; server stores only **public** keys; each token carries `sub`(device_id), `iat`, `exp`(~60s), `jti`, `bh`(base64url(sha256(body))). Reject expired, replayed `jti`, or mismatched `bh`.
- Admin actions (mint enrollment code, revoke device) require the caller's `X-authentik-groups` to include `BMSMON_ADMIN_GROUP` (default `Covert.life - Full App Access - User Group`).
- Container image name: `ghcr.io/mkeguy106/bmsmon-server`.
- All new server code lives under `server/`; web under `web/`. Do not touch `android/`.

---

## File Structure

```
server/
  pyproject.toml
  app/
    __init__.py
    config.py            # env settings
    main.py              # app factory, lifespan (pool+schema+partitions), router mounts, static
    db/
      __init__.py
      pool.py            # asyncpg pool lifecycle + get_pool(request)
      schema.sql         # DDL: devices, enrollment_codes, batteries, samples (partitioned)
      partitions.py      # month-partition helpers
      queries.py         # all SQL functions
    auth/
      __init__.py
      enroll.py          # code generate/hash
      device_jwt.py      # ES256 verify, bh + jti checks, JtiCache
      authentik.py       # header parsing + admin/current-user dependencies
    live/
      __init__.py
      bus.py             # in-process pub/sub
    models.py            # pydantic request/response models
    routers/
      __init__.py
      api_device.py      # /api/v1/enroll, /ingest, /health
      web.py             # /web/fleet, /samples, /devices, /enroll-codes, revoke
      ws.py              # /ws
  tools/
    fake_feeder.py       # enroll + stream synthetic telemetry
  tests/
    conftest.py
    test_enroll.py
    test_ingest_jwt.py
    test_web_auth.py
    test_ws_live.py
  Dockerfile
  docker-compose.dev.yml
web/
  package.json
  tsconfig.json
  vite.config.ts
  index.html
  src/
    main.tsx
    theme.css
    types.ts
    api.ts
    ws.ts
    store.ts
    store.test.ts
    App.tsx
    components/{Ring.tsx,PackCard.tsx,MainStage.tsx,AllBatteries.tsx,AdminDevices.tsx}
.github/workflows/build-server.yml
```

---

## Task 1: Server scaffold — config, DB pool, schema, `/api/v1/health`

**Files:**
- Create: `server/pyproject.toml`, `server/app/__init__.py`, `server/app/config.py`, `server/app/db/__init__.py`, `server/app/db/pool.py`, `server/app/db/schema.sql`, `server/app/db/partitions.py`, `server/app/main.py`, `server/app/routers/__init__.py`, `server/app/routers/api_device.py`, `server/docker-compose.dev.yml`, `server/tests/conftest.py`, `server/tests/test_enroll.py` (health test placeholder lives here initially)
- Test: `server/tests/test_health.py`

**Interfaces:**
- Produces: `app.config.settings` (`.database_url:str`, `.admin_group:str`, `.dev_trust_headers:bool`, `.dev_user:str`, `.dev_groups:list[str]`); `app.db.pool.get_pool(request)->asyncpg.Pool`; `app.db.partitions.ensure_partitions_for_range(conn, min_ts_ms:int, max_ts_ms:int)`; `create_app()->FastAPI`.

- [ ] **Step 1: Write `server/pyproject.toml`**

```toml
[project]
name = "bmsmon-server"
version = "0.1.0"
requires-python = ">=3.12"
dependencies = [
  "fastapi>=0.111",
  "uvicorn[standard]>=0.30",
  "asyncpg>=0.29",
  "pydantic>=2.7",
  "pyjwt>=2.8",
  "cryptography>=42",
]

[project.optional-dependencies]
dev = ["pytest>=8", "pytest-asyncio>=0.23", "httpx>=0.27"]

[tool.pytest.ini_options]
asyncio_mode = "auto"
```

- [ ] **Step 2: Write `server/app/config.py`**

```python
import os
from dataclasses import dataclass, field


def _split(v: str) -> list[str]:
    return [s for s in (p.strip() for p in v.replace("|", ",").split(",")) if s]


@dataclass(frozen=True)
class Settings:
    database_url: str = os.environ.get(
        "DATABASE_URL", "postgresql://bmsmon:bmsmon@localhost:5432/bmsmon"
    )
    admin_group: str = os.environ.get(
        "BMSMON_ADMIN_GROUP", "Covert.life - Full App Access - User Group"
    )
    # In local dev (no Authentik in front), trust a synthetic identity so /web/* works.
    dev_trust_headers: bool = os.environ.get("BMSMON_DEV_TRUST_HEADERS", "0") == "1"
    dev_user: str = os.environ.get("BMSMON_DEV_USER", "dev@covert.life")
    dev_groups: list[str] = field(
        default_factory=lambda: _split(
            os.environ.get("BMSMON_DEV_GROUPS", "Covert.life - Full App Access - User Group")
        )
    )


settings = Settings()
```

- [ ] **Step 3: Write `server/app/db/schema.sql`**

```sql
CREATE TABLE IF NOT EXISTS devices (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  install_uuid text UNIQUE NOT NULL,
  public_key_spki bytea NOT NULL,
  label text,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz,
  revoked boolean NOT NULL DEFAULT false
);

CREATE TABLE IF NOT EXISTS enrollment_codes (
  code_hash text PRIMARY KEY,
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  used_at timestamptz,
  device_id uuid REFERENCES devices(id)
);

CREATE TABLE IF NOT EXISTS batteries (
  address text PRIMARY KEY,
  advertised_name text,
  alias text,
  group_id text,
  first_seen timestamptz NOT NULL DEFAULT now(),
  last_seen timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS samples (
  device_id uuid NOT NULL,
  address text NOT NULL,
  ts_ms bigint NOT NULL,
  ts timestamptz NOT NULL,
  state text,
  soc real, current_a real, power_w real, voltage_v real,
  temp_c real, mosfet_temp_c int, soh int,
  full_charge_ah real, remaining_ah real, cycles int,
  cell_min_v real, cell_max_v real, cells jsonb,
  regen boolean NOT NULL DEFAULT false,
  link_event text,
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (device_id, address, ts_ms, ts)
) PARTITION BY RANGE (ts);

CREATE INDEX IF NOT EXISTS samples_addr_ts ON samples (address, ts DESC);
```

- [ ] **Step 4: Write `server/app/db/partitions.py`**

```python
from datetime import datetime, timezone

import asyncpg


def _month_bounds(year: int, month: int) -> tuple[str, str, str]:
    start = datetime(year, month, 1, tzinfo=timezone.utc)
    ny, nm = (year + 1, 1) if month == 12 else (year, month + 1)
    end = datetime(ny, nm, 1, tzinfo=timezone.utc)
    name = f"samples_{year:04d}_{month:02d}"
    return name, start.isoformat(), end.isoformat()


def _months_in_range(min_ms: int, max_ms: int) -> set[tuple[int, int]]:
    lo = datetime.fromtimestamp(min_ms / 1000, tz=timezone.utc)
    hi = datetime.fromtimestamp(max_ms / 1000, tz=timezone.utc)
    out: set[tuple[int, int]] = set()
    y, m = lo.year, lo.month
    while (y, m) <= (hi.year, hi.month):
        out.add((y, m))
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return out


async def ensure_partition(conn: asyncpg.Connection, year: int, month: int) -> None:
    name, start, end = _month_bounds(year, month)
    await conn.execute(
        f"CREATE TABLE IF NOT EXISTS {name} PARTITION OF samples "
        f"FOR VALUES FROM ('{start}') TO ('{end}')"
    )


async def ensure_partitions_for_range(conn: asyncpg.Connection, min_ms: int, max_ms: int) -> None:
    for y, m in sorted(_months_in_range(min_ms, max_ms)):
        await ensure_partition(conn, y, m)
```

- [ ] **Step 5: Write `server/app/db/pool.py`**

```python
from pathlib import Path

import asyncpg
from fastapi import Request

from app.config import settings

_SCHEMA = (Path(__file__).parent / "schema.sql").read_text()


async def create_pool() -> asyncpg.Pool:
    pool = await asyncpg.create_pool(settings.database_url, min_size=1, max_size=10)
    async with pool.acquire() as conn:
        await conn.execute(_SCHEMA)
    return pool


def get_pool(request: Request) -> asyncpg.Pool:
    return request.app.state.pool
```

- [ ] **Step 6: Write `server/app/routers/api_device.py` (health only for now)**

```python
from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from app.db.pool import get_pool

router = APIRouter(prefix="/api/v1")


@router.get("/health")
async def health(pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        await conn.execute("SELECT 1")
    return JSONResponse({"status": "ok"})
```

- [ ] **Step 7: Write `server/app/main.py`**

```python
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
    app.include_router(api_device.router)
    return app


app = create_app()
```

- [ ] **Step 8: Write `server/docker-compose.dev.yml`**

```yaml
services:
  db:
    image: postgres:16
    environment:
      POSTGRES_USER: bmsmon
      POSTGRES_PASSWORD: bmsmon
      POSTGRES_DB: bmsmon
    ports:
      - "5432:5432"
```

- [ ] **Step 9: Write `server/tests/conftest.py`**

```python
import asyncio

import asyncpg
import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.config import settings
from app.main import create_app


@pytest_asyncio.fixture
async def app():
    application = create_app()
    async with application.router.lifespan_context(application):
        # clean slate each test
        pool = application.state.pool
        async with pool.acquire() as conn:
            await conn.execute(
                "TRUNCATE samples, batteries, enrollment_codes, devices RESTART IDENTITY CASCADE"
            )
        yield application


@pytest_asyncio.fixture
async def client(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        yield c
```

- [ ] **Step 10: Write `server/tests/test_health.py`**

```python
async def test_health_ok(client):
    r = await client.get("/api/v1/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"
```

- [ ] **Step 11: Bring up the dev DB and run the test (expect PASS)**

```bash
cd server
docker compose -f docker-compose.dev.yml up -d db
sleep 3
pip install -e ".[dev]"
PYTHONPATH=. pytest tests/test_health.py -v
```
Expected: 1 passed (app boots, schema applies, `/api/v1/health` returns ok).

- [ ] **Step 12: Commit**

```bash
cd /home/joely/bmsmon
git add server/pyproject.toml server/app server/tests server/docker-compose.dev.yml
git commit -m "feat(server): scaffold FastAPI app, Postgres schema, health endpoint"
```

---

## Task 2: Sample queries — idempotent insert + fleet snapshot

**Files:**
- Create: `server/app/db/queries.py`
- Test: `server/tests/test_queries.py`

**Interfaces:**
- Consumes: `ensure_partitions_for_range` (Task 1).
- Produces:
  - `sample_row(device_id:str, address:str, s:dict)->dict` — normalizes one inbound sample into a DB row (adds `ts` from `ts_ms`).
  - `insert_samples(conn, rows:list[dict])->int` — ensures partitions, inserts `ON CONFLICT DO NOTHING`, returns `len(rows)`.
  - `upsert_battery(conn, address, advertised_name, alias, group_id, ts_ms:int)`.
  - `fleet_snapshot(conn)->list[dict]` — latest sample per address joined to battery meta.
  - `samples_range(conn, address:str, from_ms:int, to_ms:int)->list[dict]`.

- [ ] **Step 1: Write the failing test `server/tests/test_queries.py`**

```python
from datetime import datetime, timezone

from app.db import queries as q

A = "C8:47:80:15:67:44"
DEV = "00000000-0000-0000-0000-000000000001"


def _sample(ts_ms, soc=87.0):
    return {"ts_ms": ts_ms, "state": "Discharging", "soc": soc, "current_a": -2.5,
            "power_w": 127.5, "voltage_v": 51.0, "temp_c": 25.0, "mosfet_temp_c": 28,
            "soh": 98, "full_charge_ah": 100.0, "remaining_ah": 87.5, "cycles": 342,
            "cell_min_v": 3.17, "cell_max_v": 3.19, "cells": None, "regen": False,
            "link_event": None}


async def _device(conn):
    await conn.execute(
        "INSERT INTO devices (id, install_uuid, public_key_spki) VALUES ($1,$2,$3)",
        DEV, "uuid-1", b"\x00",
    )


async def test_insert_is_idempotent_and_snapshot_returns_latest(app):
    pool = app.state.pool
    async with pool.acquire() as conn:
        await _device(conn)
        base = int(datetime(2026, 6, 29, tzinfo=timezone.utc).timestamp() * 1000)
        rows = [q.sample_row(DEV, A, _sample(base, 80.0)),
                q.sample_row(DEV, A, _sample(base + 1500, 79.0))]
        assert await q.insert_samples(conn, rows) == 2
        # re-send the same batch -> no duplicates
        await q.insert_samples(conn, rows)
        await q.upsert_battery(conn, A, "R-12100", "2012 · A", "2012", base + 1500)

        snap = await q.fleet_snapshot(conn)
        assert len(snap) == 1
        assert snap[0]["address"] == A
        assert snap[0]["soc"] == 79.0          # the latest sample
        assert snap[0]["alias"] == "2012 · A"

        total = await conn.fetchval("SELECT count(*) FROM samples")
        assert total == 2                        # idempotent
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd server && PYTHONPATH=. pytest tests/test_queries.py -v`
Expected: FAIL (`module app.db.queries has no attribute sample_row`).

- [ ] **Step 3: Write `server/app/db/queries.py`**

```python
import json
from datetime import datetime, timezone

import asyncpg

from app.db.partitions import ensure_partitions_for_range

_COLS = ["state", "soc", "current_a", "power_w", "voltage_v", "temp_c", "mosfet_temp_c",
         "soh", "full_charge_ah", "remaining_ah", "cycles", "cell_min_v", "cell_max_v",
         "regen", "link_event"]


def sample_row(device_id: str, address: str, s: dict) -> dict:
    ts_ms = int(s["ts_ms"])
    row = {"device_id": device_id, "address": address, "ts_ms": ts_ms,
           "ts": datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc)}
    for c in _COLS:
        row[c] = s.get(c)
    cells = s.get("cells")
    row["cells"] = json.dumps(cells) if cells is not None else None
    row["regen"] = bool(s.get("regen", False))
    return row


_INSERT = """
INSERT INTO samples
  (device_id,address,ts_ms,ts,state,soc,current_a,power_w,voltage_v,temp_c,
   mosfet_temp_c,soh,full_charge_ah,remaining_ah,cycles,cell_min_v,cell_max_v,cells,regen,link_event)
VALUES
  ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20)
ON CONFLICT DO NOTHING
"""


async def insert_samples(conn: asyncpg.Connection, rows: list[dict]) -> int:
    if not rows:
        return 0
    ts_all = [r["ts_ms"] for r in rows]
    await ensure_partitions_for_range(conn, min(ts_all), max(ts_all))
    await conn.executemany(_INSERT, [
        (r["device_id"], r["address"], r["ts_ms"], r["ts"], r["state"], r["soc"],
         r["current_a"], r["power_w"], r["voltage_v"], r["temp_c"], r["mosfet_temp_c"],
         r["soh"], r["full_charge_ah"], r["remaining_ah"], r["cycles"], r["cell_min_v"],
         r["cell_max_v"], r["cells"], r["regen"], r["link_event"])
        for r in rows
    ])
    return len(rows)


async def upsert_battery(conn, address, advertised_name, alias, group_id, ts_ms: int) -> None:
    ts = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc)
    await conn.execute(
        """INSERT INTO batteries (address, advertised_name, alias, group_id, first_seen, last_seen)
           VALUES ($1,$2,$3,$4,$5,$5)
           ON CONFLICT (address) DO UPDATE SET
             advertised_name = COALESCE(EXCLUDED.advertised_name, batteries.advertised_name),
             alias = COALESCE(EXCLUDED.alias, batteries.alias),
             group_id = COALESCE(EXCLUDED.group_id, batteries.group_id),
             last_seen = GREATEST(batteries.last_seen, EXCLUDED.last_seen)""",
        address, advertised_name, alias, group_id, ts,
    )


async def fleet_snapshot(conn) -> list[dict]:
    rows = await conn.fetch(
        """SELECT DISTINCT ON (s.address) s.*, b.alias, b.group_id, b.advertised_name
           FROM samples s LEFT JOIN batteries b ON b.address = s.address
           ORDER BY s.address, s.ts DESC"""
    )
    return [dict(r) for r in rows]


async def samples_range(conn, address: str, from_ms: int, to_ms: int) -> list[dict]:
    a = datetime.fromtimestamp(from_ms / 1000, tz=timezone.utc)
    b = datetime.fromtimestamp(to_ms / 1000, tz=timezone.utc)
    rows = await conn.fetch(
        "SELECT * FROM samples WHERE address=$1 AND ts>=$2 AND ts<=$3 ORDER BY ts", address, a, b
    )
    return [dict(r) for r in rows]
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd server && PYTHONPATH=. pytest tests/test_queries.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/app/db/queries.py server/tests/test_queries.py
git commit -m "feat(server): idempotent sample insert + fleet snapshot queries"
```

---

## Task 3: Enrollment — code generation, hashing, and `POST /api/v1/enroll`

**Files:**
- Create: `server/app/auth/__init__.py`, `server/app/auth/enroll.py`, `server/app/models.py`
- Modify: `server/app/db/queries.py` (add device/code queries), `server/app/routers/api_device.py`
- Test: `server/tests/test_enroll.py`

**Interfaces:**
- Produces:
  - `app.auth.enroll.generate_code()->str` (20-char base32), `hash_code(code:str)->str` (sha256 hex).
  - queries: `create_enrollment_code(conn, code_hash, created_by, expires_at)`, `take_valid_code(conn, code_hash, now)->asyncpg.Record|None` (returns row if unused & unexpired, else None), `create_device(conn, install_uuid, public_key_spki:bytes, label)->str`(uuid), `mark_code_used(conn, code_hash, device_id, now)`, `get_device(conn, device_id)->Record|None`.
  - models: `EnrollBody(code, install_uuid, public_key_spki_b64, device_label|None)`, `EnrollResponse(device_id)`.

- [ ] **Step 1: Write the failing test `server/tests/test_enroll.py`**

```python
import base64
from datetime import datetime, timedelta, timezone

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app.auth.enroll import hash_code
from app.db import queries as q


def _pub_b64():
    priv = ec.generate_private_key(ec.SECP256R1())
    spki = priv.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return base64.b64encode(spki).decode()


async def _seed_code(app, code="ABC123", minutes=10):
    async with app.state.pool.acquire() as conn:
        await q.create_enrollment_code(
            conn, hash_code(code), "admin@covert.life",
            datetime.now(timezone.utc) + timedelta(minutes=minutes))


async def test_enroll_with_valid_code(app, client):
    await _seed_code(app, "GOODCODE")
    r = await client.post("/api/v1/enroll", json={
        "code": "GOODCODE", "install_uuid": "inst-1", "public_key_spki_b64": _pub_b64()})
    assert r.status_code == 200
    assert r.json()["device_id"]


async def test_enroll_rejects_unknown_code(client):
    r = await client.post("/api/v1/enroll", json={
        "code": "NOPE", "install_uuid": "inst-2", "public_key_spki_b64": _pub_b64()})
    assert r.status_code == 400


async def test_code_is_single_use(app, client):
    await _seed_code(app, "ONCE")
    body = {"code": "ONCE", "install_uuid": "inst-3", "public_key_spki_b64": _pub_b64()}
    assert (await client.post("/api/v1/enroll", json=body)).status_code == 200
    assert (await client.post("/api/v1/enroll", json=body)).status_code == 400
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd server && PYTHONPATH=. pytest tests/test_enroll.py -v`
Expected: FAIL (`No module named app.auth.enroll`).

- [ ] **Step 3: Write `server/app/auth/__init__.py` (empty) and `server/app/auth/enroll.py`**

```python
import hashlib
import secrets

_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def generate_code() -> str:
    return "".join(secrets.choice(_ALPHABET) for _ in range(20))


def hash_code(code: str) -> str:
    return hashlib.sha256(code.strip().encode()).hexdigest()
```

- [ ] **Step 4: Write `server/app/models.py`**

```python
from pydantic import BaseModel


class EnrollBody(BaseModel):
    code: str
    install_uuid: str
    public_key_spki_b64: str
    device_label: str | None = None


class EnrollResponse(BaseModel):
    device_id: str


class SampleIn(BaseModel):
    ts_ms: int
    address: str
    advertised_name: str | None = None
    alias: str | None = None
    group_id: str | None = None
    state: str | None = None
    soc: float | None = None
    current_a: float | None = None
    power_w: float | None = None
    voltage_v: float | None = None
    temp_c: float | None = None
    mosfet_temp_c: int | None = None
    soh: int | None = None
    full_charge_ah: float | None = None
    remaining_ah: float | None = None
    cycles: int | None = None
    cell_min_v: float | None = None
    cell_max_v: float | None = None
    cells: list[float] | None = None
    regen: bool = False
    link_event: str | None = None


class IngestBody(BaseModel):
    batch_seq: int
    samples: list[SampleIn] = []


class IngestResponse(BaseModel):
    accepted: int
    last_seq: int


class MintCodeResponse(BaseModel):
    code: str
    expires_at: str
```

- [ ] **Step 5: Append device/code queries to `server/app/db/queries.py`**

```python
async def create_enrollment_code(conn, code_hash, created_by, expires_at) -> None:
    await conn.execute(
        "INSERT INTO enrollment_codes (code_hash, created_by, expires_at) VALUES ($1,$2,$3)",
        code_hash, created_by, expires_at)


async def take_valid_code(conn, code_hash, now):
    return await conn.fetchrow(
        "SELECT * FROM enrollment_codes WHERE code_hash=$1 AND used_at IS NULL AND expires_at > $2",
        code_hash, now)


async def create_device(conn, install_uuid, public_key_spki: bytes, label) -> str:
    return await conn.fetchval(
        """INSERT INTO devices (install_uuid, public_key_spki, label) VALUES ($1,$2,$3)
           ON CONFLICT (install_uuid) DO UPDATE SET public_key_spki=EXCLUDED.public_key_spki,
             label=EXCLUDED.label, revoked=false
           RETURNING id""",
        install_uuid, public_key_spki, label)


async def mark_code_used(conn, code_hash, device_id, now) -> None:
    await conn.execute(
        "UPDATE enrollment_codes SET used_at=$2, device_id=$3 WHERE code_hash=$1",
        code_hash, now, device_id)


async def get_device(conn, device_id):
    return await conn.fetchrow("SELECT * FROM devices WHERE id=$1", device_id)
```

- [ ] **Step 6: Add the enroll route to `server/app/routers/api_device.py`**

```python
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
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd server && PYTHONPATH=. pytest tests/test_enroll.py -v`
Expected: 3 passed.

- [ ] **Step 8: Commit**

```bash
git add server/app/auth server/app/models.py server/app/db/queries.py server/app/routers/api_device.py server/tests/test_enroll.py
git commit -m "feat(server): device enrollment with single-use codes"
```

---

## Task 4: Device JWT verification + `POST /api/v1/ingest`

**Files:**
- Create: `server/app/auth/device_jwt.py`, `server/app/live/__init__.py`, `server/app/live/bus.py`
- Modify: `server/app/routers/api_device.py`, `server/app/main.py` (attach bus to app.state)
- Test: `server/tests/test_ingest_jwt.py`

**Interfaces:**
- Consumes: `get_device`, `insert_samples`, `sample_row`, `upsert_battery` (Tasks 2–3).
- Produces:
  - `app.auth.device_jwt.body_hash(body:bytes)->str` (base64url sha256, no padding).
  - `app.auth.device_jwt.JtiCache` with `seen(jti:str, exp:int)->bool` (True if replay).
  - `app.auth.device_jwt.verify(token:str, public_key_spki:bytes, body:bytes, jti_cache)->dict` (claims) — raises `JwtError` on any failure.
  - `app.live.bus.LiveBus` with `subscribe()->asyncio.Queue`, `unsubscribe(q)`, `async publish(event:dict)`.
  - Route `POST /api/v1/ingest` → `IngestResponse`.

- [ ] **Step 1: Write the failing test `server/tests/test_ingest_jwt.py`**

```python
import base64
import hashlib
import json
import time
import uuid

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app.db import queries as q

A = "C8:47:80:15:67:44"


def _keypair():
    priv = ec.generate_private_key(ec.SECP256R1())
    spki = priv.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return priv, spki


def _bh(body: bytes) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(body).digest()).rstrip(b"=").decode()


def _token(priv, device_id, body: bytes, exp_in=60, jti=None):
    now = int(time.time())
    return jwt.encode({"sub": device_id, "iat": now, "exp": now + exp_in,
                       "jti": jti or uuid.uuid4().hex, "bh": _bh(body)},
                      priv, algorithm="ES256")


async def _enroll_device(app, spki):
    async with app.state.pool.acquire() as conn:
        return str(await q.create_device(conn, "inst-x", spki, "dev"))


def _payload():
    return {"batch_seq": 7, "samples": [
        {"ts_ms": 1719686400000, "address": A, "alias": "2012 · A", "advertised_name": "R-12100",
         "group_id": "2012", "soc": 87.0, "current_a": -2.5, "power_w": 127.5, "voltage_v": 51.0,
         "temp_c": 25.0, "regen": False}]}


async def test_ingest_accepts_valid_signed_batch(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(priv, device_id, body)}",
                                   "Content-Type": "application/json"})
    assert r.status_code == 200
    assert r.json() == {"accepted": 1, "last_seq": 7}
    async with app.state.pool.acquire() as conn:
        assert await conn.fetchval("SELECT count(*) FROM samples") == 1


async def test_ingest_rejects_wrong_key(app, client):
    priv, spki = _keypair()
    other, _ = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    r = await client.post("/api/v1/ingest", content=body,
                          headers={"Authorization": f"Bearer {_token(other, device_id, body)}"})
    assert r.status_code == 401


async def test_ingest_rejects_tampered_body(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    token = _token(priv, device_id, body)
    tampered = json.dumps({**_payload(), "batch_seq": 9}).encode()
    r = await client.post("/api/v1/ingest", content=tampered,
                          headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 401


async def test_ingest_rejects_replayed_jti(app, client):
    priv, spki = _keypair()
    device_id = await _enroll_device(app, spki)
    body = json.dumps(_payload()).encode()
    token = _token(priv, device_id, body, jti="fixed-jti")
    h = {"Authorization": f"Bearer {token}"}
    assert (await client.post("/api/v1/ingest", content=body, headers=h)).status_code == 200
    assert (await client.post("/api/v1/ingest", content=body, headers=h)).status_code == 401
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd server && PYTHONPATH=. pytest tests/test_ingest_jwt.py -v`
Expected: FAIL (no `device_jwt` / `/ingest`).

- [ ] **Step 3: Write `server/app/live/__init__.py` (empty) and `server/app/live/bus.py`**

```python
import asyncio


class LiveBus:
    def __init__(self) -> None:
        self._subs: set[asyncio.Queue] = set()

    def subscribe(self) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue(maxsize=1000)
        self._subs.add(q)
        return q

    def unsubscribe(self, q: asyncio.Queue) -> None:
        self._subs.discard(q)

    async def publish(self, event: dict) -> None:
        for q in list(self._subs):
            try:
                q.put_nowait(event)
            except asyncio.QueueFull:
                pass  # slow consumer drops; it re-snapshots on next connect
```

- [ ] **Step 4: Write `server/app/auth/device_jwt.py`**

```python
import base64
import hashlib
import time

import jwt
from cryptography.hazmat.primitives.serialization import load_der_public_key


class JwtError(Exception):
    pass


def body_hash(body: bytes) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(body).digest()).rstrip(b"=").decode()


class JtiCache:
    def __init__(self) -> None:
        self._seen: dict[str, int] = {}

    def seen(self, jti: str, exp: int) -> bool:
        now = int(time.time())
        self._seen = {k: v for k, v in self._seen.items() if v > now}  # prune
        if jti in self._seen:
            return True
        self._seen[jti] = exp
        return False


def unverified_sub(token: str) -> str:
    try:
        return jwt.decode(token, options={"verify_signature": False})["sub"]
    except Exception as e:
        raise JwtError("no sub") from e


def verify(token: str, public_key_spki: bytes, body: bytes, jti_cache: JtiCache) -> dict:
    try:
        pub = load_der_public_key(public_key_spki)
        claims = jwt.decode(token, pub, algorithms=["ES256"],
                            options={"require": ["exp", "sub", "jti"]}, leeway=120)
    except Exception as e:
        raise JwtError(str(e)) from e
    if claims.get("bh") != body_hash(body):
        raise JwtError("body hash mismatch")
    if jti_cache.seen(claims["jti"], int(claims["exp"])):
        raise JwtError("replay")
    return claims
```

- [ ] **Step 5: Attach a `JtiCache` and `LiveBus` in `server/app/main.py`**

Add inside `create_app()` after `app = FastAPI(...)`:

```python
    from app.auth.device_jwt import JtiCache
    from app.live.bus import LiveBus
    app.state.jti_cache = JtiCache()
    app.state.bus = LiveBus()
```

- [ ] **Step 6: Add the ingest route to `server/app/routers/api_device.py`**

```python
import json

from fastapi import Request
from app.auth.device_jwt import JwtError, unverified_sub, verify
from app.models import IngestBody, IngestResponse


@router.post("/ingest", response_model=IngestResponse)
async def ingest(request: Request, pool=Depends(get_pool)):
    auth = request.headers.get("authorization", "")
    if not auth.lower().startswith("bearer "):
        raise HTTPException(401, "missing bearer")
    token = auth[7:]
    raw = await request.body()
    try:
        device_id = unverified_sub(token)
    except JwtError:
        raise HTTPException(401, "bad token")
    async with pool.acquire() as conn:
        dev = await q.get_device(conn, device_id)
        if dev is None or dev["revoked"]:
            raise HTTPException(401, "unknown or revoked device")
        try:
            verify(token, bytes(dev["public_key_spki"]), raw, request.app.state.jti_cache)
        except JwtError:
            raise HTTPException(401, "bad signature")
        body = IngestBody.model_validate_json(raw)
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
```

Note: `sample_row` ignores keys it doesn't use (`address`/`alias`/etc. are handled separately), so passing `s.model_dump()` is safe.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd server && PYTHONPATH=. pytest tests/test_ingest_jwt.py -v`
Expected: 4 passed.

- [ ] **Step 8: Commit**

```bash
git add server/app/auth/device_jwt.py server/app/live server/app/main.py server/app/routers/api_device.py server/tests/test_ingest_jwt.py
git commit -m "feat(server): ES256-signed idempotent ingest with replay+body binding"
```

---

## Task 5: Live WebSocket — `/ws` snapshot + broadcast

**Files:**
- Create: `server/app/routers/ws.py`
- Modify: `server/app/main.py` (include ws router)
- Test: `server/tests/test_ws_live.py`

**Interfaces:**
- Consumes: `LiveBus` (Task 4), `fleet_snapshot` (Task 2), `current_user` is NOT required here in dev (Authentik gates it in prod; see Task 6 note).
- Produces: `WS /ws` — first message `{"type":"snapshot","fleet":[...]}`, then `{"type":"sample",...}` per ingest.

- [ ] **Step 1: Write the failing test `server/tests/test_ws_live.py`**

```python
import base64
import json
import time
import uuid

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from starlette.testclient import TestClient

from app.db import queries as q


def _kp():
    priv = ec.generate_private_key(ec.SECP256R1())
    spki = priv.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return priv, spki


async def test_ws_sends_snapshot_then_live_sample(app):
    priv, spki = _kp()
    async with app.state.pool.acquire() as conn:
        device_id = str(await q.create_device(conn, "inst-ws", spki, "dev"))
    # TestClient drives the same ASGI app (sync) for the websocket leg.
    with TestClient(app) as tc:
        with tc.websocket_connect("/ws") as ws:
            first = ws.receive_json()
            assert first["type"] == "snapshot"
            body = json.dumps({"batch_seq": 1, "samples": [
                {"ts_ms": int(time.time() * 1000), "address": "C8:47:80:15:67:44",
                 "soc": 55.0, "alias": "2012 · A"}]}).encode()
            import hashlib
            bh = base64.urlsafe_b64encode(hashlib.sha256(body).digest()).rstrip(b"=").decode()
            tok = jwt.encode({"sub": device_id, "iat": int(time.time()),
                              "exp": int(time.time()) + 60, "jti": uuid.uuid4().hex, "bh": bh},
                             priv, algorithm="ES256")
            tc.post("/api/v1/ingest", content=body, headers={"Authorization": f"Bearer {tok}"})
            evt = ws.receive_json()
            assert evt["type"] == "sample"
            assert evt["soc"] == 55.0
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd server && PYTHONPATH=. pytest tests/test_ws_live.py -v`
Expected: FAIL (no `/ws` route).

- [ ] **Step 3: Write `server/app/routers/ws.py`**

```python
import asyncio

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app.db import queries as q

router = APIRouter()


def _jsonable(rows: list[dict]) -> list[dict]:
    out = []
    for r in rows:
        d = dict(r)
        for k, v in list(d.items()):
            if hasattr(v, "isoformat"):
                d[k] = v.isoformat()
        out.append(d)
    return out


@router.websocket("/ws")
async def ws(sock: WebSocket):
    await sock.accept()
    pool = sock.app.state.pool
    bus = sock.app.state.bus
    async with pool.acquire() as conn:
        fleet = await q.fleet_snapshot(conn)
    await sock.send_json({"type": "snapshot", "fleet": _jsonable(fleet)})
    queue = bus.subscribe()
    try:
        while True:
            event = await queue.get()
            await sock.send_json(event)
    except (WebSocketDisconnect, asyncio.CancelledError):
        pass
    finally:
        bus.unsubscribe(queue)
```

- [ ] **Step 4: Include the router in `server/app/main.py`**

Add `from app.routers import api_device, ws` and `app.include_router(ws.router)` in `create_app()`.

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd server && PYTHONPATH=. pytest tests/test_ws_live.py -v`
Expected: 1 passed.

- [ ] **Step 6: Commit**

```bash
git add server/app/routers/ws.py server/app/main.py server/tests/test_ws_live.py
git commit -m "feat(server): live WebSocket with snapshot + ingest broadcast"
```

---

## Task 6: Browser zone — Authentik headers, group gate, `/web/*`

**Files:**
- Create: `server/app/auth/authentik.py`, `server/app/routers/web.py`
- Modify: `server/app/main.py` (include web router), `server/app/db/queries.py` (list/revoke devices)
- Test: `server/tests/test_web_auth.py`

**Interfaces:**
- Consumes: `settings.admin_group/dev_*`, `fleet_snapshot`, `samples_range`, `generate_code`, `hash_code`, `create_enrollment_code`.
- Produces:
  - `app.auth.authentik.AuthUser(username:str, groups:list[str])`.
  - `current_user(request)->AuthUser` (401 if no identity and not dev-trusted).
  - `require_admin(request)->AuthUser` (403 unless `admin_group` in groups).
  - queries: `list_devices(conn)->list[dict]`, `revoke_device(conn, device_id)`.
  - Routes: `GET /web/fleet`, `GET /web/samples`, `GET /web/devices`, `POST /web/enroll-codes`, `DELETE /web/devices/{id}`.

- [ ] **Step 1: Write the failing test `server/tests/test_web_auth.py`**

```python
ADMIN = "Covert.life - Full App Access - User Group"


async def test_fleet_requires_identity(client):
    r = await client.get("/web/fleet")
    assert r.status_code == 401


async def test_fleet_ok_with_authentik_headers(client):
    r = await client.get("/web/fleet", headers={
        "X-authentik-username": "joel", "X-authentik-groups": ADMIN})
    assert r.status_code == 200
    assert r.json()["fleet"] == []


async def test_mint_code_requires_admin_group(client):
    r = await client.post("/web/enroll-codes", headers={
        "X-authentik-username": "rando", "X-authentik-groups": "Some Other Group"})
    assert r.status_code == 403


async def test_admin_can_mint_code(client):
    r = await client.post("/web/enroll-codes", headers={
        "X-authentik-username": "joel", "X-authentik-groups": ADMIN})
    assert r.status_code == 200
    assert len(r.json()["code"]) == 20
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd server && PYTHONPATH=. pytest tests/test_web_auth.py -v`
Expected: FAIL (no `/web/*`).

- [ ] **Step 3: Write `server/app/auth/authentik.py`**

```python
from dataclasses import dataclass

from fastapi import HTTPException, Request

from app.config import settings


@dataclass
class AuthUser:
    username: str
    groups: list[str]


def _split_groups(v: str) -> list[str]:
    return [g.strip() for g in v.replace("|", ",").split(",") if g.strip()]


def current_user(request: Request) -> AuthUser:
    username = request.headers.get("x-authentik-username")
    if username:
        return AuthUser(username, _split_groups(request.headers.get("x-authentik-groups", "")))
    if settings.dev_trust_headers:
        return AuthUser(settings.dev_user, list(settings.dev_groups))
    raise HTTPException(401, "not authenticated")


def require_admin(request: Request) -> AuthUser:
    user = current_user(request)
    if settings.admin_group not in user.groups:
        raise HTTPException(403, "admin group required")
    return user
```

- [ ] **Step 4: Append device list/revoke queries to `server/app/db/queries.py`**

```python
async def list_devices(conn) -> list[dict]:
    rows = await conn.fetch(
        "SELECT id, install_uuid, label, created_at, last_seen_at, revoked FROM devices ORDER BY created_at")
    return [dict(r) for r in rows]


async def revoke_device(conn, device_id) -> None:
    await conn.execute("UPDATE devices SET revoked=true WHERE id=$1", device_id)
```

- [ ] **Step 5: Write `server/app/routers/web.py`**

```python
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
```

- [ ] **Step 6: Include the router in `server/app/main.py`**

Add `from app.routers import api_device, web, ws` and `app.include_router(web.router)`.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd server && PYTHONPATH=. pytest tests/test_web_auth.py -v`
Expected: 4 passed. Then run the whole server suite: `PYTHONPATH=. pytest -v` → all green.

- [ ] **Step 8: Commit**

```bash
git add server/app/auth/authentik.py server/app/routers/web.py server/app/db/queries.py server/app/main.py server/tests/test_web_auth.py
git commit -m "feat(server): browser zone /web/* with Authentik header auth + admin group gate"
```

---

## Task 7: Fake telemetry feeder

**Files:**
- Create: `server/tools/fake_feeder.py`

**Interfaces:**
- Consumes: `/api/v1/enroll`, `/api/v1/ingest` (live server). Standalone CLI; no test cycle (it IS the manual integration exerciser).

- [ ] **Step 1: Write `server/tools/fake_feeder.py`**

```python
"""Enroll a synthetic device and stream fake telemetry so the WebUI shows live data.

Usage:
  1) Mint a code (admin):  curl -XPOST -H 'X-authentik-username: dev' \
       -H 'X-authentik-groups: Covert.life - Full App Access - User Group' \
       http://localhost:8000/web/enroll-codes
     ...or run with BMSMON_DEV_TRUST_HEADERS=1 and use --mint.
  2) python tools/fake_feeder.py --base http://localhost:8000 --code <CODE>
"""
import argparse
import base64
import hashlib
import json
import math
import time
import urllib.request
import uuid

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

PACKS = [("C8:47:80:15:67:44", "2012 · A", "2012"), ("C8:47:80:15:62:1B", "2012 · B", "2012"),
         ("C8:47:80:15:DB:13", "2016 · A", "2016"), ("C8:47:80:15:25:9A", "2016 · B", "2016")]


def _post(url, data, headers=None):
    req = urllib.request.Request(url, data=data, headers=headers or {}, method="POST")
    with urllib.request.urlopen(req) as r:
        return r.status, r.read()


def _bh(body: bytes) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(body).digest()).rstrip(b"=").decode()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8000")
    ap.add_argument("--code", required=True)
    ap.add_argument("--mint", action="store_true", help="mint the code first (needs dev-trust)")
    args = ap.parse_args()

    code = args.code
    if args.mint:
        _, b = _post(args.base + "/web/enroll-codes", b"", {
            "X-authentik-username": "dev",
            "X-authentik-groups": "Covert.life - Full App Access - User Group"})
        code = json.loads(b)["code"]
        print("minted code", code)

    priv = ec.generate_private_key(ec.SECP256R1())
    spki = priv.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    _, b = _post(args.base + "/api/v1/enroll", json.dumps({
        "code": code, "install_uuid": str(uuid.uuid4()),
        "public_key_spki_b64": base64.b64encode(spki).decode()}).encode(),
        {"Content-Type": "application/json"})
    device_id = json.loads(b)["device_id"]
    print("enrolled device", device_id)

    seq = 0
    while True:
        seq += 1
        t = time.time()
        samples = []
        for i, (addr, alias, gid) in enumerate(PACKS):
            soc = 60 + 20 * math.sin(t / 30 + i)
            cur = -3 * (1 + math.sin(t / 7 + i))
            samples.append({"ts_ms": int(t * 1000), "address": addr, "alias": alias,
                            "advertised_name": "R-12100", "group_id": gid, "soc": round(soc, 1),
                            "current_a": round(cur, 2), "power_w": round(abs(cur) * 51, 1),
                            "voltage_v": 51.0, "temp_c": 25.0,
                            "state": "Discharging" if cur < -0.1 else "Idle", "regen": False})
        body = json.dumps({"batch_seq": seq, "samples": samples}).encode()
        token = jwt.encode({"sub": device_id, "iat": int(t), "exp": int(t) + 60,
                            "jti": uuid.uuid4().hex, "bh": _bh(body)}, priv, algorithm="ES256")
        st, _ = _post(args.base + "/api/v1/ingest", body, {"Authorization": f"Bearer {token}"})
        print("batch", seq, "->", st)
        time.sleep(1.5)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Manually verify against a running server**

```bash
cd server
BMSMON_DEV_TRUST_HEADERS=1 PYTHONPATH=. uvicorn app.main:app --port 8000 &
sleep 2
python tools/fake_feeder.py --base http://localhost:8000 --code X --mint
```
Expected: prints `minted code …`, `enrolled device …`, then `batch N -> 200` every ~1.5s. Stop with Ctrl-C; `kill %1`.

- [ ] **Step 3: Commit**

```bash
git add server/tools/fake_feeder.py
git commit -m "feat(server): fake telemetry feeder for live end-to-end demo"
```

---

## Task 8: Dockerfile + static serving

**Files:**
- Create: `server/Dockerfile`
- Modify: `server/app/main.py` (mount `web/dist` as static if present)

**Interfaces:**
- Consumes: the React build at `web/dist` (Task 12). Until then, the static mount is a no-op (guarded by existence check), so the server still runs.

- [ ] **Step 1: Mount static in `server/app/main.py`**

Add at the end of `create_app()` (before `return app`):

```python
    import os
    from fastapi.staticfiles import StaticFiles
    web_dist = os.environ.get("BMSMON_WEB_DIST", "/app/web/dist")
    if os.path.isdir(web_dist):
        app.mount("/", StaticFiles(directory=web_dist, html=True), name="web")
```

- [ ] **Step 2: Write `server/Dockerfile` (multi-stage: build web, run api)**

```dockerfile
# --- web build ---
FROM node:20-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json* ./
RUN npm ci || npm install
COPY web/ ./
RUN npm run build

# --- api runtime ---
FROM python:3.12-slim
WORKDIR /app
COPY server/pyproject.toml ./
RUN pip install --no-cache-dir .
COPY server/app ./app
COPY --from=web /web/dist ./web/dist
ENV BMSMON_WEB_DIST=/app/web/dist
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

- [ ] **Step 3: Verify the API image builds and serves health (web dist may be empty pre-Task-12)**

```bash
cd /home/joely/bmsmon
docker build -f server/Dockerfile -t bmsmon-server:test .
docker run --rm -e DATABASE_URL=postgresql://bmsmon:bmsmon@host.docker.internal:5432/bmsmon \
  -p 8000:8000 bmsmon-server:test &
sleep 4 && curl -fsS http://localhost:8000/api/v1/health && echo OK
docker stop $(docker ps -q --filter ancestor=bmsmon-server:test)
```
Expected: `{"status":"ok"}` then `OK`. (Web build runs but is harmless if `web/` is a stub; the real UI lands in Tasks 10–13. If `web/` does not exist yet, comment out the web stage temporarily — note it for the executor.)

- [ ] **Step 4: Commit**

```bash
git add server/Dockerfile server/app/main.py
git commit -m "build(server): multi-stage Dockerfile + static web serving"
```

---

## Task 9: GHCR build workflow

**Files:**
- Create: `.github/workflows/build-server.yml`

- [ ] **Step 1: Write `.github/workflows/build-server.yml`**

```yaml
name: build-server
on:
  push:
    branches: [main, master]
    paths: ["server/**", "web/**", ".github/workflows/build-server.yml"]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - uses: docker/build-push-action@v6
        with:
          context: .
          file: server/Dockerfile
          push: true
          tags: |
            ghcr.io/mkeguy106/bmsmon-server:latest
            ghcr.io/mkeguy106/bmsmon-server:${{ github.sha }}
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/build-server.yml
git commit -m "ci: build and push bmsmon-server image to GHCR"
```

---

## Task 10: WebUI scaffold — types, theme, api, ws, live store (with tests)

**Files:**
- Create: `web/package.json`, `web/tsconfig.json`, `web/vite.config.ts`, `web/index.html`, `web/src/main.tsx`, `web/src/theme.css`, `web/src/types.ts`, `web/src/api.ts`, `web/src/ws.ts`, `web/src/store.ts`
- Test: `web/src/store.test.ts`

**Interfaces:**
- Produces:
  - `types.ts`: `Sample` (mirrors server fields), `FleetItem = Sample & {alias?:string; group_id?:string}`.
  - `store.ts`: `createStore()` → `{ getFleet():Record<string,FleetItem>, applySnapshot(fleet:FleetItem[]), applySample(s:Sample), subscribe(fn):()=>void }`. Keyed by `address`; `applySample` replaces only if `ts_ms` is newer.
  - `ws.ts`: `connectLive(onSnapshot, onSample, onStatus)` with auto-reconnect.
  - `api.ts`: `getFleet()`, `mintCode()`, `getDevices()`, `revokeDevice(id)`.

- [ ] **Step 1: Write `web/package.json`**

```json
{
  "name": "bmsmon-web",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "test": "vitest run"
  },
  "dependencies": { "react": "^18.3.1", "react-dom": "^18.3.1" },
  "devDependencies": {
    "@types/react": "^18.3.3", "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.1", "typescript": "^5.5.3",
    "vite": "^5.3.4", "vitest": "^2.0.4"
  }
}
```

- [ ] **Step 2: Write `web/tsconfig.json`, `web/vite.config.ts`, `web/index.html`, `web/src/main.tsx`**

`web/tsconfig.json`:
```json
{
  "compilerOptions": {
    "target": "ES2020", "useDefineForClassFields": true, "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext", "skipLibCheck": true, "moduleResolution": "bundler",
    "jsx": "react-jsx", "strict": true, "noEmit": true
  },
  "include": ["src"]
}
```

`web/vite.config.ts` (proxy `/web`, `/api`, `/ws` to the API during `npm run dev`):
```ts
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/web": "http://localhost:8000",
      "/api": "http://localhost:8000",
      "/ws": { target: "ws://localhost:8000", ws: true },
    },
  },
});
```

`web/index.html`:
```html
<!doctype html>
<html lang="en">
  <head><meta charset="utf-8" /><title>bmsmon</title>
    <link rel="preconnect" href="https://fonts.googleapis.com" />
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600;700&display=swap" rel="stylesheet" />
  </head>
  <body><div id="root"></div><script type="module" src="/src/main.tsx"></script></body>
</html>
```

`web/src/main.tsx`:
```tsx
import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./theme.css";

createRoot(document.getElementById("root")!).render(<React.StrictMode><App /></React.StrictMode>);
```

- [ ] **Step 3: Write `web/src/theme.css` (mirror the phone's `Bm` dark tokens)**

```css
:root {
  --bg: #121212; --card: #161616; --border: #2a2a2a; --divider: #1f1f1f;
  --input-bg: #1d1d1d; --input-border: #333; --text: #ececec; --text2: #8a8a8a;
  --text3: #777; --accent: #e67e22; --power: #c85a1a; --regen: #2ecc71; --critical: #e5342b;
  --sans: Inter, system-ui, sans-serif; --mono: "JetBrains Mono", monospace;
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--text); font-family: var(--sans); }
.mono { font-family: var(--mono); }
```

- [ ] **Step 4: Write `web/src/types.ts`**

```ts
export interface Sample {
  address: string; ts_ms: number; state?: string | null;
  soc?: number | null; current_a?: number | null; power_w?: number | null;
  voltage_v?: number | null; temp_c?: number | null; soh?: number | null;
  cycles?: number | null; regen?: boolean; link_event?: string | null;
}
export type FleetItem = Sample & { alias?: string | null; group_id?: string | null };
export interface DeviceRow {
  id: string; install_uuid: string; label?: string | null;
  last_seen_at?: string | null; revoked: boolean;
}
```

- [ ] **Step 5: Write the failing store test `web/src/store.test.ts`**

```ts
import { describe, expect, it } from "vitest";
import { createStore } from "./store";

describe("store", () => {
  it("keeps the newest sample per address", () => {
    const s = createStore();
    s.applySnapshot([{ address: "A", ts_ms: 100, soc: 50, alias: "2012 · A" }]);
    s.applySample({ address: "A", ts_ms: 200, soc: 60 });
    expect(s.getFleet()["A"].soc).toBe(60);
    s.applySample({ address: "A", ts_ms: 150, soc: 99 }); // older -> ignored
    expect(s.getFleet()["A"].soc).toBe(60);
    expect(s.getFleet()["A"].alias).toBe("2012 · A"); // meta preserved
  });

  it("notifies subscribers on change", () => {
    const s = createStore();
    let calls = 0;
    s.subscribe(() => { calls++; });
    s.applySample({ address: "B", ts_ms: 1, soc: 10 });
    expect(calls).toBe(1);
  });
});
```

- [ ] **Step 6: Run it to verify it fails**

Run: `cd web && npm install && npm test`
Expected: FAIL (`createStore` not found).

- [ ] **Step 7: Write `web/src/store.ts`, `web/src/ws.ts`, `web/src/api.ts`**

`web/src/store.ts`:
```ts
import type { FleetItem, Sample } from "./types";

export function createStore() {
  const fleet: Record<string, FleetItem> = {};
  const subs = new Set<() => void>();
  const notify = () => subs.forEach((f) => f());

  const merge = (s: Sample, meta?: Partial<FleetItem>) => {
    const cur = fleet[s.address];
    if (cur && cur.ts_ms >= s.ts_ms && !meta) return false;
    fleet[s.address] = { ...cur, ...meta, ...s };
    return true;
  };

  return {
    getFleet: () => fleet,
    applySnapshot(items: FleetItem[]) {
      items.forEach((i) => merge(i, i));
      notify();
    },
    applySample(s: Sample) {
      if (merge(s)) notify();
    },
    subscribe(fn: () => void) { subs.add(fn); return () => subs.delete(fn); },
  };
}
export type Store = ReturnType<typeof createStore>;
```

`web/src/ws.ts`:
```ts
import type { FleetItem, Sample } from "./types";

export function connectLive(
  onSnapshot: (f: FleetItem[]) => void,
  onSample: (s: Sample) => void,
  onStatus: (connected: boolean) => void,
) {
  let ws: WebSocket | null = null;
  let stop = false;
  const open = () => {
    const proto = location.protocol === "https:" ? "wss" : "ws";
    ws = new WebSocket(`${proto}://${location.host}/ws`);
    ws.onopen = () => onStatus(true);
    ws.onmessage = (e) => {
      const msg = JSON.parse(e.data);
      if (msg.type === "snapshot") onSnapshot(msg.fleet);
      else if (msg.type === "sample") onSample(msg);
    };
    ws.onclose = () => { onStatus(false); if (!stop) setTimeout(open, 1500); };
    ws.onerror = () => ws?.close();
  };
  open();
  return () => { stop = true; ws?.close(); };
}
```

`web/src/api.ts`:
```ts
import type { DeviceRow, FleetItem } from "./types";

const j = async (r: Response) => { if (!r.ok) throw new Error(String(r.status)); return r.json(); };

export const getFleet = (): Promise<{ fleet: FleetItem[] }> => fetch("/web/fleet").then(j);
export const getDevices = (): Promise<{ devices: DeviceRow[] }> => fetch("/web/devices").then(j);
export const mintCode = (): Promise<{ code: string; expires_at: string }> =>
  fetch("/web/enroll-codes", { method: "POST" }).then(j);
export const revokeDevice = (id: string): Promise<unknown> =>
  fetch(`/web/devices/${id}`, { method: "DELETE" }).then(j);
```

- [ ] **Step 8: Run the store test to verify it passes**

Run: `cd web && npm test`
Expected: 2 passed.

- [ ] **Step 9: Commit**

```bash
git add web/package.json web/tsconfig.json web/vite.config.ts web/index.html web/src/main.tsx web/src/theme.css web/src/types.ts web/src/store.ts web/src/store.test.ts web/src/ws.ts web/src/api.ts
git commit -m "feat(web): scaffold React app, live store + ws client, theme tokens"
```

---

## Task 11: Pack visualization — `Ring` + `PackCard`

**Files:**
- Create: `web/src/components/Ring.tsx`, `web/src/components/PackCard.tsx`

**Interfaces:**
- Consumes: `FleetItem` (Task 10).
- Produces:
  - `Ring({ soc, connected, size }: { soc: number|null; connected: boolean; size: number })` — SVG SOC ring (accent ramp: <15 critical, <30 warn, else accent; dimmed when disconnected).
  - `PackCard({ item, stale }: { item: FleetItem; stale: boolean })` — compact card: alias, ring, SOC %, and a stat grid (power/current/voltage/temp), `DISCONNECTED` treatment when `stale`.

- [ ] **Step 1: Write `web/src/components/Ring.tsx`**

```tsx
export function Ring({ soc, connected, size = 120 }:
  { soc: number | null; connected: boolean; size?: number }) {
  const r = size / 2 - 8, c = 2 * Math.PI * r;
  const pct = connected && soc != null ? Math.max(0, Math.min(100, soc)) : 0;
  const color = !connected ? "var(--text3)"
    : pct < 15 ? "var(--critical)" : pct < 30 ? "#e2b01e" : "var(--accent)";
  return (
    <svg width={size} height={size} style={{ opacity: connected ? 1 : 0.4 }}>
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--input-bg)" strokeWidth={8} />
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={color} strokeWidth={8}
        strokeLinecap="round" strokeDasharray={c} strokeDashoffset={c * (1 - pct / 100)}
        transform={`rotate(-90 ${size / 2} ${size / 2})`} />
      <text x="50%" y="50%" textAnchor="middle" dy="0.35em" className="mono"
        fill="var(--text)" fontSize={size * 0.22} fontWeight={700}>
        {connected && soc != null ? `${Math.round(soc)}%` : "—"}
      </text>
    </svg>
  );
}
```

- [ ] **Step 2: Write `web/src/components/PackCard.tsx`**

```tsx
import type { FleetItem } from "../types";
import { Ring } from "./Ring";

const Stat = ({ label, value }: { label: string; value: string }) => (
  <div style={{ background: "var(--input-bg)", borderRadius: 8, padding: "8px 10px" }}>
    <div style={{ color: "var(--text3)", fontSize: 10, letterSpacing: 1 }} className="mono">{label}</div>
    <div className="mono" style={{ color: "var(--text)", fontSize: 15, fontWeight: 600 }}>{value}</div>
  </div>
);

export function PackCard({ item, stale }: { item: FleetItem; stale: boolean }) {
  const connected = !stale;
  const n = (v: number | null | undefined, d = 1) => (v == null ? "—" : v.toFixed(d));
  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--border)", borderRadius: 14,
      padding: 16, display: "flex", flexDirection: "column", alignItems: "center", gap: 10, minWidth: 220 }}>
      <div style={{ color: "var(--text2)", fontWeight: 600 }}>{item.alias ?? item.address}</div>
      <Ring soc={item.soc ?? null} connected={connected} size={120} />
      {!connected && <div className="mono" style={{ color: "var(--text3)", fontSize: 11, letterSpacing: 2 }}>DISCONNECTED</div>}
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8, width: "100%" }}>
        <Stat label="POWER" value={connected ? `${n(item.power_w, 0)} W` : "—"} />
        <Stat label="CURRENT" value={connected ? `${n(item.current_a)} A` : "—"} />
        <Stat label="VOLTAGE" value={connected ? `${n(item.voltage_v, 2)} V` : "—"} />
        <Stat label="TEMP" value={connected ? `${n(item.temp_c, 0)}°C` : "—"} />
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Type-check (no runtime test for pure presentational SVG)**

Run: `cd web && npx tsc -b`
Expected: no type errors.

- [ ] **Step 4: Commit**

```bash
git add web/src/components/Ring.tsx web/src/components/PackCard.tsx
git commit -m "feat(web): SOC ring + pack card with DISCONNECTED treatment"
```

---

## Task 12: Live dashboard — `MainStage`, `AllBatteries`, `App`

**Files:**
- Create: `web/src/components/MainStage.tsx`, `web/src/components/AllBatteries.tsx`
- Modify: `web/src/App.tsx` (create)

**Interfaces:**
- Consumes: `createStore`, `connectLive`, `PackCard`, `Ring`, `FleetItem`.
- Produces: `App` — connects live, renders a header (live/offline pill), Main Stage (the discharging base, or the most-recently-updated pack) large, and All Batteries grid. A pack is `stale` if its `ts_ms` is older than 10s.
- Staleness uses a 1s ticking clock so cards flip to DISCONNECTED without new samples.

- [ ] **Step 1: Write `web/src/components/MainStage.tsx`**

```tsx
import type { FleetItem } from "../types";
import { Ring } from "./Ring";

export function MainStage({ items, staleAddrs }:
  { items: FleetItem[]; staleAddrs: Set<string> }) {
  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--border)", borderRadius: 16, padding: 24 }}>
      <div className="mono" style={{ color: "var(--text3)", fontSize: 11, letterSpacing: 2, marginBottom: 16 }}>MAIN STAGE</div>
      <div style={{ display: "flex", gap: 32, justifyContent: "center", flexWrap: "wrap" }}>
        {items.length === 0 && <div style={{ color: "var(--text3)" }}>No active base</div>}
        {items.map((it) => {
          const connected = !staleAddrs.has(it.address);
          return (
            <div key={it.address} style={{ textAlign: "center" }}>
              <div style={{ color: "var(--text)", fontWeight: 700, fontSize: 20, marginBottom: 12 }}>{it.alias ?? it.address}</div>
              <Ring soc={it.soc ?? null} connected={connected} size={200} />
              <div className="mono" style={{ color: "var(--power)", marginTop: 12, fontSize: 18 }}>
                {connected ? `${(it.power_w ?? 0).toFixed(0)} W · ${(it.current_a ?? 0).toFixed(1)} A` : "DISCONNECTED"}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Write `web/src/components/AllBatteries.tsx`**

```tsx
import type { FleetItem } from "../types";
import { PackCard } from "./PackCard";

export function AllBatteries({ items, staleAddrs }:
  { items: FleetItem[]; staleAddrs: Set<string> }) {
  return (
    <div>
      <div className="mono" style={{ color: "var(--text3)", fontSize: 11, letterSpacing: 2, margin: "8px 4px 16px" }}>ALL BATTERIES</div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))", gap: 16 }}>
        {items.map((it) => <PackCard key={it.address} item={it} stale={staleAddrs.has(it.address)} />)}
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Write `web/src/App.tsx`**

```tsx
import { useEffect, useMemo, useRef, useState } from "react";
import { AllBatteries } from "./components/AllBatteries";
import { MainStage } from "./components/MainStage";
import { connectLive } from "./ws";
import { createStore } from "./store";
import type { FleetItem } from "./types";

const STALE_MS = 10_000;

export default function App() {
  const store = useRef(createStore()).current;
  const [, force] = useState(0);
  const [live, setLive] = useState(false);
  const [now, setNow] = useState(Date.now());

  useEffect(() => store.subscribe(() => force((n) => n + 1)), [store]);
  useEffect(() => connectLive(store.applySnapshot, store.applySample, setLive), [store]);
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);

  const items: FleetItem[] = useMemo(
    () => Object.values(store.getFleet()).sort((a, b) => (a.alias ?? "").localeCompare(b.alias ?? "")),
    [store, now],
  );
  const staleAddrs = useMemo(
    () => new Set(items.filter((i) => now - i.ts_ms > STALE_MS).map((i) => i.address)),
    [items, now],
  );
  // Main stage = discharging packs that are fresh; fall back to the most recently updated.
  const stage = items.filter((i) => !staleAddrs.has(i.address) && (i.current_a ?? 0) < -0.1);
  const stageItems = stage.length ? stage : items.slice().sort((a, b) => b.ts_ms - a.ts_ms).slice(0, 1);

  return (
    <div style={{ maxWidth: 1400, margin: "0 auto", padding: 24 }}>
      <header style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 24 }}>
        <h1 style={{ fontSize: 22, margin: 0 }}>bmsmon</h1>
        <span style={{ marginLeft: "auto", display: "flex", alignItems: "center", gap: 8,
          color: live ? "var(--regen)" : "var(--text3)", fontSize: 13 }}>
          <span style={{ width: 9, height: 9, borderRadius: "50%",
            background: live ? "var(--regen)" : "var(--text3)" }} />
          {live ? "LIVE" : "RECONNECTING…"}
        </span>
      </header>
      <div style={{ display: "grid", gap: 24 }}>
        <MainStage items={stageItems} staleAddrs={staleAddrs} />
        <AllBatteries items={items} staleAddrs={staleAddrs} />
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Build, then verify live end-to-end**

```bash
cd web && npm run build           # tsc + vite build → web/dist
# In one shell: run API with dev-trust so /ws + /web work without Authentik
cd ../server && BMSMON_DEV_TRUST_HEADERS=1 BMSMON_WEB_DIST=$PWD/../web/dist PYTHONPATH=. uvicorn app.main:app --port 8000 &
sleep 2
# In another: stream fake data
python tools/fake_feeder.py --base http://localhost:8000 --code X --mint
```
Open `http://localhost:8000/` → expect the LIVE pill green, Main Stage ring animating, four pack cards updating ~every 1.5s. Stop the feeder and watch cards flip to DISCONNECTED after 10s. `kill %1` when done.

- [ ] **Step 5: Commit**

```bash
git add web/src/components/MainStage.tsx web/src/components/AllBatteries.tsx web/src/App.tsx
git commit -m "feat(web): live Main Stage + All Batteries dashboard on one page"
```

---

## Task 13: Admin — enroll code + device list

**Files:**
- Create: `web/src/components/AdminDevices.tsx`
- Modify: `web/src/App.tsx` (add a collapsible admin panel)

**Interfaces:**
- Consumes: `mintCode`, `getDevices`, `revokeDevice` (Task 10 `api.ts`).
- Produces: `AdminDevices()` — "Enroll device" button → shows the one-time code (big mono text), a device table (label, last seen, revoked) with a Revoke action.

- [ ] **Step 1: Write `web/src/components/AdminDevices.tsx`**

```tsx
import { useEffect, useState } from "react";
import { getDevices, mintCode, revokeDevice } from "../api";
import type { DeviceRow } from "../types";

export function AdminDevices() {
  const [devices, setDevices] = useState<DeviceRow[]>([]);
  const [code, setCode] = useState<string | null>(null);
  const refresh = () => getDevices().then((d) => setDevices(d.devices)).catch(() => {});
  useEffect(() => { refresh(); }, []);

  return (
    <div style={{ background: "var(--card)", border: "1px solid var(--border)", borderRadius: 14, padding: 16 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
        <strong>Devices</strong>
        <button style={{ marginLeft: "auto" }}
          onClick={() => mintCode().then((r) => setCode(r.code)).catch(() => alert("Not authorized"))}>
          Enroll device
        </button>
      </div>
      {code && (
        <div style={{ margin: "12px 0", padding: 12, background: "var(--input-bg)", borderRadius: 10 }}>
          <div style={{ color: "var(--text3)", fontSize: 12 }}>One-time code (expires in 10 min):</div>
          <div className="mono" style={{ fontSize: 24, color: "var(--accent)", letterSpacing: 3 }}>{code}</div>
        </div>
      )}
      <table style={{ width: "100%", marginTop: 12, borderCollapse: "collapse", fontSize: 13 }}>
        <thead><tr style={{ color: "var(--text3)", textAlign: "left" }}>
          <th>Label</th><th>Last seen</th><th></th></tr></thead>
        <tbody>
          {devices.map((d) => (
            <tr key={d.id} style={{ borderTop: "1px solid var(--border)", opacity: d.revoked ? 0.4 : 1 }}>
              <td style={{ padding: "8px 0" }}>{d.label ?? d.install_uuid}</td>
              <td className="mono">{d.last_seen_at ?? "—"}</td>
              <td style={{ textAlign: "right" }}>
                {!d.revoked && <button onClick={() => revokeDevice(d.id).then(refresh).catch(() => alert("Not authorized"))}>Revoke</button>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

- [ ] **Step 2: Add the admin panel to `web/src/App.tsx`**

Add `import { AdminDevices } from "./components/AdminDevices";` and place `<AdminDevices />` as the last child of the `<div style={{ display: "grid", gap: 24 }}>` block (after `<AllBatteries .../>`).

- [ ] **Step 3: Build + verify**

```bash
cd web && npm run build && npx tsc -b
```
Expected: clean build. With the API running dev-trust (Task 12), reload `http://localhost:8000/` → "Enroll device" mints a code; the device table lists the fake feeder's device after it enrolls.

- [ ] **Step 4: Commit**

```bash
git add web/src/components/AdminDevices.tsx web/src/App.tsx
git commit -m "feat(web): admin panel — mint enrollment code + device list/revoke"
```

---

## Self-Review (completed during planning)

- **Spec coverage:** enroll/ingest/health (T3–T4), JWT keypair auth incl. `bh`+`jti`+exp (T4), idempotent partitioned storage mirroring `SampleEntity` (T1–T2), `/web/*` with Authentik headers + admin-group gate on minting/revoke (T6), live `/ws` snapshot+broadcast (T5), React Main-Stage + All-Batteries one-page live UI with DISCONNECTED (T10–T12), admin enroll/revoke UI (T13), fake feeder (T7), Dockerfile + static serve + GHCR (T8–T9). Phone reporter, historical import, and NAS deploy are explicitly **Phase 2/3** (separate plans).
- **Placeholder scan:** every code step is concrete; the only conditional note is the Dockerfile web-stage when `web/` is a stub (Task 8 Step 3), with explicit instruction.
- **Type consistency:** server sample field names match across `schema.sql`, `queries.sample_row`, `models.SampleIn`; `device_id`/`bh`/`jti` consistent across `device_jwt`, `api_device`, tests, and feeder; web `FleetItem`/`Sample` consistent across `store`, `ws`, components.

## Out of scope (later phases)

- **Phase 2:** phone `TelemetryReporter`, Room outbox, Keystore ES256 signer, `onPoll` tap, one-time historical importer, "Cloud sync" settings page.
- **Phase 3:** `qnap-nas-docker/bmsmon/` compose + Traefik split-routing + Authentik/DNS labels, `.env` (`BMSMON_DB_PASSWORD`, `BMSMON_ADMIN_GROUP`), Uptime Kuma monitors.
