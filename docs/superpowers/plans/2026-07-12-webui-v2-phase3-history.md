# WebUI v2 — Phase 3 (History) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the History view live — three per-base trend charts (SOH fade, cell imbalance, temperature) with base/AB/range controls, a charge-session log, and backend-persisted notes — backed by three new `/web` endpoints.

**Architecture:** Additive server work (`trend_series` + adaptive bucket; `charge_session_buckets` + a pure `detect_charge_sessions`; a `web_notes` table + GET/POST). New web data layer (`trends.ts` types, decoders, api fns, a pure `model/trends.ts`), a reusable inline-SVG `LineChart`, and `HistoryView` wired into the shell. No v1 or Phase-1/2 behavior changes.

**Tech Stack:** React 18 + Vite + TypeScript + vitest (web); FastAPI + asyncpg + Postgres 16 + pytest (server).

## Global Constraints

- **No new npm dependencies.** Charts are inline SVG — no chart library.
- **v1 is never modified**, and Command/Fleet Health/Alerts/Settings behavior is unchanged. Shared files (`api.ts`, `decode.ts`, `web.py`, `queries.py`, `models.py`, `schema.sql`) may only be extended additively. **Leave `web/src/v2/history.ts` (Phase-2 `HistPoint`) untouched** — Phase-3 types go in a new `web/src/v2/trends.ts`.
- **New endpoints are read-only except `POST /web/notes`**, all `current_user` (NOT admin). `POST /web/notes` is the WebUI's first write path (Authentik-gated by Traefik).
- **Schema changes are additive** (`CREATE TABLE IF NOT EXISTS`) — auto-apply on container start; no migration step.
- **Reuse, don't reimplement:** `groupBases`/`DAILY_DRIVER_BASE` (`fleet.ts`), `Segmented`/`Bar`/`StatTile`/`Chip` (v2 components), `formatTemp`/`TempUnit` (`temp.ts`), `useV2Settings`. Follow the existing decoder style (tolerant: drop malformed, non-array→warn+null) and the `test_web_history.py` route-test pattern (`client`/`app` fixtures + `USER` header).
- **All colors via CSS variables** (tokens).
- **Local dev/test:** web `cd web && npm test`; server `cd server && .venv/bin/python -m pytest` (dev DB: `docker compose -f server/docker-compose.dev.yml up -d`).

---

## Part A — Backend

### Task 1: `GET /web/trends` (adaptive-bucket per-pack trend)

**Files:**
- Modify: `server/app/db/queries.py` (`trend_bucket_ms`, `trend_series`, `first_sample_ms`)
- Modify: `server/app/routers/web.py` (route)
- Test: `server/tests/test_trends.py` (create), `server/tests/test_web_trends.py` (create)

**Interfaces:**
- Produces: `GET /web/trends?address=&from_ms=&to_ms=` → `{ address, bucket_ms, first_ms, points:[{t,soh,cell_spread_mv,temp_avg,temp_min,temp_max}] }`. `trend_bucket_ms(span_ms)->int`; `trend_series(conn, address, from_ms, to_ms, bucket_ms)->list[dict]`; `first_sample_ms(conn, address)->int|None`.

- [ ] **Step 1: Write the failing tests**

Create `server/tests/test_trends.py` (unit: bucket sizing + aggregation), adapting the fixture pattern from `server/tests/test_cells.py`/`test_history.py`:

```python
import pytest
from app.db import queries as q

pytestmark = pytest.mark.asyncio

def test_trend_bucket_ms_thresholds():
    D = 86_400_000
    assert q.trend_bucket_ms(1 * D) == 1_800_000
    assert q.trend_bucket_ms(10 * D) == 21_600_000
    assert q.trend_bucket_ms(60 * D) == 86_400_000
    assert q.trend_bucket_ms(400 * D) == 604_800_000

async def test_trend_series_aggregates(conn, a_device_and_battery):
    device_id, address = a_device_and_battery
    bucket = 86_400_000
    base = 100 * bucket
    rows = [
        q.sample_row(device_id, address, {"ts_ms": base + 1000, "soh": 99, "temp_c": 20.0,
                                          "cell_min_v": 3.30, "cell_max_v": 3.34}),
        q.sample_row(device_id, address, {"ts_ms": base + 2000, "soh": 97, "temp_c": 30.0,
                                          "cell_min_v": 3.31, "cell_max_v": 3.33}),
    ]
    await q.insert_samples(conn, rows)
    pts = await q.trend_series(conn, address, base, base + bucket, bucket)
    assert len(pts) == 1
    p = pts[0]
    assert p["bucket_ms"] == base
    assert round(p["soh"]) == 98
    assert round(p["cell_spread_mv"]) == 30   # avg of 40mV and 20mV
    assert p["temp_min"] == 20.0 and p["temp_max"] == 30.0
    assert await q.first_sample_ms(conn, address) == base + 1000
```

Create `server/tests/test_web_trends.py` mirroring `test_web_history.py` (401 without identity; a seeded round-trip asserting `{address,bucket_ms,first_ms,points}` shape).

- [ ] **Step 2: Run to verify they fail**

Run: `cd server && .venv/bin/python -m pytest tests/test_trends.py tests/test_web_trends.py -v`
Expected: FAIL (functions/route absent).

- [ ] **Step 3: Implement the queries**

In `server/app/db/queries.py`:

```python
def trend_bucket_ms(span_ms: int) -> int:
    D = 86_400_000
    if span_ms <= 2 * D: return 1_800_000       # 30 min
    if span_ms <= 14 * D: return 21_600_000     # 6 h
    if span_ms <= 92 * D: return 86_400_000     # 1 day
    return 604_800_000                          # 7 days


async def trend_series(conn, address: str, from_ms: int, to_ms: int, bucket_ms: int) -> list[dict]:
    rows = await conn.fetch(
        """SELECT (ts_ms / $4) * $4 AS bucket_ms,
                  avg(soh)::real AS soh,
                  avg((cell_max_v - cell_min_v) * 1000)::real AS cell_spread_mv,
                  avg(temp_c)::real AS temp_avg, min(temp_c)::real AS temp_min, max(temp_c)::real AS temp_max
             FROM samples
            WHERE address = $1 AND ts_ms >= $2 AND ts_ms < $3 AND link_event IS NULL
            GROUP BY bucket_ms ORDER BY bucket_ms""",
        address, from_ms, to_ms, bucket_ms,
    )
    return [dict(r) for r in rows]


async def first_sample_ms(conn, address: str) -> int | None:
    return await conn.fetchval(
        "SELECT min(ts_ms) FROM samples WHERE address = $1 AND link_event IS NULL", address)
```

- [ ] **Step 4: Add the route**

In `server/app/routers/web.py`:

```python
@router.get("/trends")
async def trends(address: str, from_ms: int = Query(...), to_ms: int = Query(...),
                 user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
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
```

Add a small helper near the top of web.py (or reuse if one exists): `def _f(v): return float(v) if v is not None else None`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd server && .venv/bin/python -m pytest tests/test_trends.py tests/test_web_trends.py -v` — Expected: PASS.
Then full suite: `cd server && .venv/bin/python -m pytest` — Expected: green.

- [ ] **Step 6: Commit**

```bash
git add server/app/db/queries.py server/app/routers/web.py server/tests/test_trends.py server/tests/test_web_trends.py
git commit -m "feat(server): /web/trends adaptive-bucket per-pack SOH/cell-spread/temp series"
```

---

### Task 2: `GET /web/charge-sessions` (detection)

**Files:**
- Create: `server/app/charge_sessions.py` (pure `detect_charge_sessions`)
- Modify: `server/app/db/queries.py` (`charge_session_buckets`)
- Modify: `server/app/routers/web.py` (route)
- Test: `server/tests/test_charge_sessions.py` (create — the detection unit tests are the centerpiece), `server/tests/test_web_charge_sessions.py` (route)

**Interfaces:**
- Produces: `detect_charge_sessions(buckets, *, gap_ms=900_000, soc_full=99, cv_soc=98) -> list[dict]` (newest-first, each `{start_ms,end_ms,from_soc,duration_min,cv_tail_min,peak_temp_c}`); `charge_session_buckets(conn, address, since_ms)->list[dict]` (1-min charging-only buckets `{bucket_ms,soc,temp_max}`); `GET /web/charge-sessions?address=&days=`.

- [ ] **Step 1: Write the failing detection tests**

Create `server/tests/test_charge_sessions.py`:

```python
from app.charge_sessions import detect_charge_sessions

M = 60_000  # one 1-min bucket

def b(i, soc, temp=25.0):
    return {"bucket_ms": i * M, "soc": soc, "temp_max": temp}

def test_empty():
    assert detect_charge_sessions([]) == []

def test_one_full_session():
    buckets = [b(0, 60), b(1, 80), b(2, 98, 30.0), b(3, 100, 31.0)]
    s = detect_charge_sessions(buckets)
    assert len(s) == 1
    assert s[0]["from_soc"] == 60
    assert s[0]["duration_min"] == 3
    assert s[0]["cv_tail_min"] == 2         # buckets with soc >= 98 (98 and 100)
    assert s[0]["peak_temp_c"] == 31.0

def test_incomplete_run_dropped():
    assert detect_charge_sessions([b(0, 60), b(1, 80), b(2, 90)]) == []  # never reaches 99

def test_gap_splits_two_sessions():
    a = [b(0, 60), b(1, 100)]
    later = [b(100, 55), b(101, 99)]        # 100-min gap (> 15 min) → separate
    s = detect_charge_sessions(a + later)
    assert len(s) == 2
    assert s[0]["start_ms"] == 100 * M      # newest first
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd server && .venv/bin/python -m pytest tests/test_charge_sessions.py -v` — Expected: FAIL (module absent).

- [ ] **Step 3: Implement detection**

Create `server/app/charge_sessions.py`:

```python
def _flush(run, out, soc_full, cv_soc):
    if not run:
        return
    socs = [x["soc"] for x in run if x["soc"] is not None]
    if not socs or max(socs) < soc_full:
        return
    start_ms, end_ms = run[0]["bucket_ms"], run[-1]["bucket_ms"]
    from_soc = next((x["soc"] for x in run if x["soc"] is not None), None)
    temps = [x["temp_max"] for x in run if x["temp_max"] is not None]
    out.append({
        "start_ms": int(start_ms), "end_ms": int(end_ms),
        "from_soc": round(from_soc) if from_soc is not None else None,
        "duration_min": round((end_ms - start_ms) / 60_000),
        "cv_tail_min": sum(1 for x in run if x["soc"] is not None and x["soc"] >= cv_soc),
        "peak_temp_c": max(temps) if temps else None,
    })


def detect_charge_sessions(buckets, *, gap_ms=900_000, soc_full=99, cv_soc=98):
    """Group ascending 1-min charging buckets into sessions (a gap > gap_ms splits a run),
    keep runs whose peak SOC reaches soc_full, and summarize each. Newest-first."""
    out, run, prev = [], [], None
    for x in buckets:
        if prev is not None and x["bucket_ms"] - prev > gap_ms:
            _flush(run, out, soc_full, cv_soc)
            run = []
        run.append(x)
        prev = x["bucket_ms"]
    _flush(run, out, soc_full, cv_soc)
    out.sort(key=lambda s: s["start_ms"], reverse=True)
    return out
```

- [ ] **Step 4: Implement the source query + route**

In `queries.py`:

```python
async def charge_session_buckets(conn, address: str, since_ms: int) -> list[dict]:
    """1-minute buckets of charging-only rows (current_a > 0.1) since since_ms."""
    rows = await conn.fetch(
        """SELECT (ts_ms / 60000) * 60000 AS bucket_ms,
                  avg(soc)::real AS soc, max(temp_c)::real AS temp_max
             FROM samples
            WHERE address = $1 AND ts_ms >= $2 AND link_event IS NULL AND current_a > 0.1
            GROUP BY bucket_ms ORDER BY bucket_ms""",
        address, since_ms,
    )
    return [dict(r) for r in rows]
```

In `web.py` (add `from app.charge_sessions import detect_charge_sessions` at top):

```python
@router.get("/charge-sessions")
async def charge_sessions(address: str, days: int = Query(30, ge=1, le=365),
                          user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    since_ms = int(time.time() * 1000) - days * 86_400_000
    async with pool.acquire() as conn:
        buckets = await q.charge_session_buckets(conn, address, since_ms)
    return {"sessions": detect_charge_sessions([
        {"bucket_ms": int(b["bucket_ms"]), "soc": _f(b["soc"]), "temp_max": _f(b["temp_max"])} for b in buckets
    ])}
```

- [ ] **Step 5: Write + run the route test**

Create `server/tests/test_web_charge_sessions.py` (mirror `test_web_history.py`: 401 without identity; seed a charging ramp to 100% and assert one session in the JSON). Run:
`cd server && .venv/bin/python -m pytest tests/test_charge_sessions.py tests/test_web_charge_sessions.py -v` — Expected: PASS. Then full suite green.

- [ ] **Step 6: Commit**

```bash
git add server/app/charge_sessions.py server/app/db/queries.py server/app/routers/web.py server/tests/test_charge_sessions.py server/tests/test_web_charge_sessions.py
git commit -m "feat(server): /web/charge-sessions with pure detection over charging buckets"
```

---

### Task 3: `web_notes` table + GET/POST `/web/notes`

**Files:**
- Modify: `server/app/db/schema.sql` (table), `server/app/models.py` (`NoteBody`), `server/app/db/queries.py` (`get_notes`, `upsert_note`), `server/app/routers/web.py` (routes)
- Test: `server/tests/test_web_notes.py` (create)

**Interfaces:**
- Produces: `GET /web/notes` → `{ notes:[{base_id,body,updated_at_ms}] }`; `POST /web/notes` body `NoteBody{base_id,body}` → `OkResponse`. `get_notes(conn)`, `upsert_note(conn, base_id, body, updated_at_ms)`.

- [ ] **Step 1: Write the failing test**

Create `server/tests/test_web_notes.py` (mirror `test_web_history.py` fixtures/USER header):

```python
async def test_notes_round_trip_and_upsert(client):
    assert (await client.get("/web/notes", headers=USER)).json() == {"notes": []}
    r = await client.post("/web/notes", headers=USER, json={"base_id": "2012", "body": "swapped B in 2023"})
    assert r.status_code == 200
    got = (await client.get("/web/notes", headers=USER)).json()["notes"]
    assert got == [{"base_id": "2012", "body": "swapped B in 2023", "updated_at_ms": got[0]["updated_at_ms"]}]
    await client.post("/web/notes", headers=USER, json={"base_id": "2012", "body": "updated"})
    got2 = (await client.get("/web/notes", headers=USER)).json()["notes"]
    assert len(got2) == 1 and got2[0]["body"] == "updated"

async def test_notes_requires_identity(client):
    assert (await client.get("/web/notes")).status_code == 401

async def test_notes_over_length_rejected(client):
    r = await client.post("/web/notes", headers=USER, json={"base_id": "2012", "body": "x" * 5000})
    assert r.status_code == 422
```

(Copy the exact `client`/`USER` mechanism from `test_web_history.py` — including the `conftest.py` TRUNCATE list if it enumerates tables, add `web_notes` there if needed.)

- [ ] **Step 2: Run to verify it fails**

Run: `cd server && .venv/bin/python -m pytest tests/test_web_notes.py -v` — Expected: FAIL.

- [ ] **Step 3: Add the table + model + queries**

`schema.sql` (near the other `CREATE TABLE IF NOT EXISTS`):

```sql
CREATE TABLE IF NOT EXISTS web_notes (
  base_id text PRIMARY KEY,
  body text NOT NULL,
  updated_at_ms bigint NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now()
);
```

`models.py`:

```python
class NoteBody(BaseModel):
    base_id: str
    body: str

    @field_validator("body")
    @classmethod
    def _cap(cls, v: str) -> str:
        if len(v) > 4000:
            raise ValueError("body too long")
        return v
```

`queries.py`:

```python
async def get_notes(conn) -> list[dict]:
    rows = await conn.fetch("SELECT base_id, body, updated_at_ms FROM web_notes ORDER BY base_id")
    return [dict(r) for r in rows]


async def upsert_note(conn, base_id: str, body: str, updated_at_ms: int) -> None:
    await conn.execute(
        """INSERT INTO web_notes (base_id, body, updated_at_ms) VALUES ($1, $2, $3)
           ON CONFLICT (base_id) DO UPDATE SET body = EXCLUDED.body, updated_at_ms = EXCLUDED.updated_at_ms""",
        base_id, body, updated_at_ms)
```

- [ ] **Step 4: Add the routes**

`web.py` (add `from app.models import MintCodeResponse, NoteBody, OkResponse` — extend the existing import):

```python
@router.get("/notes")
async def notes(user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        return {"notes": jsonable(await q.get_notes(conn))}


@router.post("/notes", response_model=OkResponse)
async def post_note(body: NoteBody, user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        await q.upsert_note(conn, body.base_id, body.body, int(time.time() * 1000))
    return OkResponse(ok=True)
```

(Check `OkResponse`'s actual field in `models.py:110` and construct it accordingly.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd server && .venv/bin/python -m pytest tests/test_web_notes.py -v` — Expected: PASS. Then full suite green.

- [ ] **Step 6: Commit**

```bash
git add server/app/db/schema.sql server/app/models.py server/app/db/queries.py server/app/routers/web.py server/tests/test_web_notes.py
git commit -m "feat(server): web_notes table + GET/POST /web/notes (first WebUI write path)"
```

---

## Part B — Web data layer

### Task 4: `trends.ts` types + decoders + api

**Files:**
- Create: `web/src/v2/trends.ts`
- Modify: `web/src/decode.ts` (3 decoders), `web/src/api.ts` (4 fns)
- Test: `web/src/decode.test.ts` (add cases)

**Interfaces:**
- Produces: `TrendPoint`/`TrendSeries`/`ChargeSession`/`NoteRow` types; `decodeTrends`/`decodeChargeSessions`/`decodeNotes`; `getTrends(address, fromMs, toMs)`/`getChargeSessions(address, days)`/`getNotes()`/`putNote(baseId, body)`.

- [ ] **Step 1: Write the failing tests**

Add to `web/src/decode.test.ts`:

```ts
import { decodeTrends, decodeChargeSessions, decodeNotes } from "./decode";

it("decodes a trend series (nullable metrics)", () => {
  const s = decodeTrends({ address: "AA", bucket_ms: 1800000, first_ms: 100,
    points: [{ t: 100, soh: 99, cell_spread_mv: 30, temp_avg: 20, temp_min: 18, temp_max: 22 },
             { t: 200, soh: null, cell_spread_mv: null, temp_avg: null, temp_min: null, temp_max: null }] });
  expect(s?.address).toBe("AA");
  expect(s?.points.length).toBe(2);
  expect(s?.points[1].soh).toBeNull();
});
it("decodeTrends returns null for a malformed root", () => {
  expect(decodeTrends({ address: 5 })).toBeNull();
});
it("decodes charge sessions, dropping malformed rows", () => {
  const s = decodeChargeSessions([{ start_ms: 1, end_ms: 2, from_soc: 60, duration_min: 3, cv_tail_min: 1, peak_temp_c: 30 },
                                  { start_ms: "x" }]);
  expect(s?.length).toBe(1);
});
it("decodes notes", () => {
  expect(decodeNotes([{ base_id: "2012", body: "hi", updated_at_ms: 5 }])?.[0].base_id).toBe("2012");
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd web && npm test -- decode` — Expected: FAIL.

- [ ] **Step 3: Implement types + decoders + api**

Create `web/src/v2/trends.ts`:

```ts
export interface TrendPoint {
  t: number; soh: number | null; cell_spread_mv: number | null;
  temp_avg: number | null; temp_min: number | null; temp_max: number | null;
}
export interface TrendSeries { address: string; bucket_ms: number; first_ms: number | null; points: TrendPoint[] }
export interface ChargeSession {
  start_ms: number; end_ms: number; from_soc: number | null;
  duration_min: number; cv_tail_min: number; peak_temp_c: number | null;
}
export interface NoteRow { base_id: string; body: string; updated_at_ms: number }
```

In `web/src/decode.ts` (reuse `isObj`/`warn`; a small `numOrNull = (v) => (v == null || Number.isFinite(v) ? (v ?? null) : undefined)` helper for nullable metrics):

```ts
import type { TrendSeries, ChargeSession, NoteRow } from "./v2/trends";

const numOrNull = (v: unknown): number | null | undefined =>
  v == null ? null : (Number.isFinite(v) ? (v as number) : undefined);

export function decodeTrends(x: unknown): TrendSeries | null {
  if (!isObj(x) || typeof x.address !== "string" || !Number.isFinite(x.bucket_ms) || !Array.isArray(x.points))
    return warn("trends", x);
  const first_ms = x.first_ms == null ? null : (Number.isFinite(x.first_ms) ? (x.first_ms as number) : null);
  const points = [];
  for (const p of x.points) {
    if (!isObj(p) || !Number.isFinite(p.t)) continue;
    const soh = numOrNull(p.soh), csm = numOrNull(p.cell_spread_mv),
      ta = numOrNull(p.temp_avg), tmn = numOrNull(p.temp_min), tmx = numOrNull(p.temp_max);
    if ([soh, csm, ta, tmn, tmx].some((v) => v === undefined)) continue;
    points.push({ t: p.t as number, soh, cell_spread_mv: csm, temp_avg: ta, temp_min: tmn, temp_max: tmx } as TrendPoint);
  }
  return { address: x.address, bucket_ms: x.bucket_ms as number, first_ms, points };
}

export function decodeChargeSessions(x: unknown): ChargeSession[] | null {
  if (!Array.isArray(x)) return warn("charge-sessions", x);
  const out: ChargeSession[] = [];
  for (const s of x) {
    if (!isObj(s) || !Number.isFinite(s.start_ms) || !Number.isFinite(s.end_ms) ||
        !Number.isFinite(s.duration_min) || !Number.isFinite(s.cv_tail_min)) continue;
    out.push({ start_ms: s.start_ms as number, end_ms: s.end_ms as number,
      from_soc: numOrNull(s.from_soc) ?? null, duration_min: s.duration_min as number,
      cv_tail_min: s.cv_tail_min as number, peak_temp_c: numOrNull(s.peak_temp_c) ?? null });
  }
  return out;
}

export function decodeNotes(x: unknown): NoteRow[] | null {
  if (!Array.isArray(x)) return warn("notes", x);
  const out: NoteRow[] = [];
  for (const n of x) {
    if (isObj(n) && typeof n.base_id === "string" && typeof n.body === "string" && Number.isFinite(n.updated_at_ms))
      out.push({ base_id: n.base_id, body: n.body, updated_at_ms: n.updated_at_ms as number });
  }
  return out;
}
```

In `web/src/api.ts`:

```ts
import { decodeTrends, decodeChargeSessions, decodeNotes } from "./decode";
import type { TrendSeries, ChargeSession, NoteRow } from "./v2/trends";

export const getTrends = async (address: string, fromMs: number, toMs: number): Promise<TrendSeries> => {
  const r = await fetch(`/web/trends?address=${encodeURIComponent(address)}&from_ms=${fromMs}&to_ms=${toMs}`).then(j);
  const s = decodeTrends(r);
  if (!s) throw new Error("malformed /web/trends response");
  return s;
};
export const getChargeSessions = async (address: string, days = 30): Promise<{ sessions: ChargeSession[] }> => {
  const r = await fetch(`/web/charge-sessions?address=${encodeURIComponent(address)}&days=${days}`).then(j);
  const sessions = isObj(r) ? decodeChargeSessions((r as { sessions?: unknown }).sessions) : null;
  if (!sessions) throw new Error("malformed /web/charge-sessions response");
  return { sessions };
};
export const getNotes = async (): Promise<{ notes: NoteRow[] }> => {
  const r = await fetch("/web/notes").then(j);
  const notes = isObj(r) ? decodeNotes((r as { notes?: unknown }).notes) : null;
  if (!notes) throw new Error("malformed /web/notes response");
  return { notes };
};
export const putNote = (baseId: string, body: string): Promise<unknown> =>
  fetch("/web/notes", { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ base_id: baseId, body }) }).then(j);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd web && npm test -- decode && npx tsc -b` — Expected: PASS + clean.

- [ ] **Step 5: Commit**

```bash
git add web/src/v2/trends.ts web/src/decode.ts web/src/api.ts web/src/decode.test.ts
git commit -m "feat(web): trends/charge-session/notes types, decoders, and api clients"
```

---

### Task 5: pure `model/trends.ts` (SOH projection + bands)

**Files:**
- Create: `web/src/v2/model/trends.ts`
- Test: `web/src/v2/model/trends.test.ts`

**Interfaces:**
- Produces: `projectMonthsTo80(points: {t:number; soh:number|null}[], opts?): number | null`; `sohBand(soh:number): "good"|"fair"|"degraded"`.

- [ ] **Step 1: Write the failing tests**

Create `web/src/v2/model/trends.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { projectMonthsTo80, sohBand } from "./trends";

const DAY = 86_400_000;
const line = (n: number, soh0: number, perDay: number) =>
  Array.from({ length: n }, (_, i) => ({ t: i * DAY, soh: soh0 + perDay * i }));

describe("projectMonthsTo80", () => {
  it("projects months for a steady decline", () => {
    const m = projectMonthsTo80(line(20, 100, -0.5)); // ~ -0.5 soh/day from ~90 at last point
    expect(m).not.toBeNull();
    expect(m!).toBeGreaterThan(0);
  });
  it("null for a flat/rising trend", () => {
    expect(projectMonthsTo80(line(20, 95, 0))).toBeNull();
    expect(projectMonthsTo80(line(20, 90, 0.2))).toBeNull();
  });
  it("null with too few points", () => {
    expect(projectMonthsTo80(line(3, 100, -1))).toBeNull();
  });
});

describe("sohBand", () => {
  it("bands", () => {
    expect(sohBand(95)).toBe("good");
    expect(sohBand(85)).toBe("fair");
    expect(sohBand(70)).toBe("degraded");
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd web && npm test -- trends` — Expected: FAIL.

- [ ] **Step 3: Implement**

Create `web/src/v2/model/trends.ts`:

```ts
const DAY_MS = 86_400_000;
const MONTH_DAYS = 30.44;

/** Months until the least-squares SOH fit reaches 80%. null when < minPoints, span < minSpanDays,
 *  or the trend isn't declining (slope >= 0) — the UI shows "insufficient data". */
export function projectMonthsTo80(
  points: { t: number; soh: number | null }[],
  { minPoints = 6, minSpanDays = 10 }: { minPoints?: number; minSpanDays?: number } = {},
): number | null {
  const pts = points.filter((p): p is { t: number; soh: number } => p.soh != null && Number.isFinite(p.soh));
  if (pts.length < minPoints) return null;
  const t0 = pts[0].t;
  const xs = pts.map((p) => (p.t - t0) / DAY_MS);
  const ys = pts.map((p) => p.soh);
  if (xs[xs.length - 1] - xs[0] < minSpanDays) return null;
  const n = xs.length;
  const mx = xs.reduce((a, b) => a + b, 0) / n, my = ys.reduce((a, b) => a + b, 0) / n;
  let num = 0, den = 0;
  for (let i = 0; i < n; i++) { num += (xs[i] - mx) * (ys[i] - my); den += (xs[i] - mx) ** 2; }
  if (den === 0) return null;
  const slope = num / den;               // soh per day
  if (slope >= 0) return null;           // not declining
  const fittedLast = my + slope * (xs[n - 1] - mx);
  const daysTo80 = (fittedLast - 80) / -slope;
  if (!Number.isFinite(daysTo80) || daysTo80 <= 0) return null;
  return daysTo80 / MONTH_DAYS;
}

export function sohBand(soh: number): "good" | "fair" | "degraded" {
  return soh >= 90 ? "good" : soh >= 80 ? "fair" : "degraded";
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd web && npm test -- trends && npx tsc -b` — Expected: PASS + clean.

- [ ] **Step 5: Commit**

```bash
git add web/src/v2/model/trends.ts web/src/v2/model/trends.test.ts
git commit -m "feat(web): SOH-to-80% projection + health-band model"
```

---

## Part C — Components & view

### Task 6: `LineChart` component

**Files:**
- Create: `web/src/v2/components/LineChart.tsx`

**Interfaces:**
- Produces: `LineChart({ series, bands, watchLine, ribbon, yMin, yMax, height, unitLabel })` — inline-SVG multi-series line chart with optional shaded y-bands, a horizontal watch-line, and an optional min/avg/max ribbon; time x-axis; token-styled; empty/insufficient → muted "not enough data".

- [ ] **Step 1: Implement**

Create `web/src/v2/components/LineChart.tsx`. Prose spec:
- Props: `series: { points: {t:number; v:number|null}[]; color: string; label?: string }[]` (one for Group, or two for A/B); `bands?: { from:number; to:number; color:string }[]` (SOH health bands, drawn as faint horizontal rects); `watchLine?: { v:number; color:string; label?:string }` (40 mV); `ribbon?: { points: {t:number; lo:number|null; hi:number|null}[]; color:string }` (temp min/max fill); `yMin`/`yMax` (or auto from data); `height` (default ~180); `unitLabel?`.
- Renders a responsive full-width SVG (use a fixed viewBox width e.g. 600 and `width:100%`), left y-axis with 3–4 gridlines + range labels, bottom x-axis with a few time ticks (format as short dates from `t`), each series as a `polyline` skipping null gaps (break the path on nulls), ribbon as a filled `path` between lo/hi, bands as background rects, watch-line as a dashed horizontal line + small label.
- All colors via tokens/passed-in `color` (which callers set to `var(--ok)`/`var(--warn)`/`var(--live)`/`var(--text-3)`). Muted empty state when every series has < 2 non-null points.

```tsx
export interface ChartSeries { points: { t: number; v: number | null }[]; color: string; label?: string }
export function LineChart({ series, bands, watchLine, ribbon, yMin, yMax, height = 180, unitLabel }: {
  series: ChartSeries[];
  bands?: { from: number; to: number; color: string }[];
  watchLine?: { v: number; color: string; label?: string };
  ribbon?: { points: { t: number; lo: number | null; hi: number | null }[]; color: string };
  yMin?: number; yMax?: number; height?: number; unitLabel?: string;
}) { /* … inline SVG … */ }
```

- [ ] **Step 2: Verify**

Run: `cd web && npx tsc -b && npm run build` — Expected: clean + builds.

- [ ] **Step 3: Commit**

```bash
git add web/src/v2/components/LineChart.tsx
git commit -m "feat(web): reusable inline-SVG LineChart (series, bands, watch-line, ribbon)"
```

---

### Task 7: `HistoryView` — controls + charts + header

**Files:**
- Create: `web/src/v2/views/HistoryView.tsx`
- Create: `web/src/v2/useTrends.ts` (fetch hook for the selected pack(s)/range)

**Interfaces:**
- Consumes: `FleetData`, `groupBases`/`DAILY_DRIVER_BASE`, `getTrends`, `TrendSeries`, `projectMonthsTo80`/`sohBand`, `LineChart`, `Segmented`, `formatTemp`/`TempUnit`.
- Produces: `HistoryView({ data, unit, mobile })`; `useTrends(addresses, fromMs, toMs)` → `Map<address, TrendSeries>` (refetch on deps).

- [ ] **Step 1: Implement useTrends**

Create `web/src/v2/useTrends.ts`: given `addresses: string[]`, `fromMs`, `toMs`, fetch `getTrends` for each address (in parallel), return `Map<address, TrendSeries>`; `alive` guard; refetch when the args change (key on `addresses.join(",")+fromMs+toMs`).

- [ ] **Step 2: Implement HistoryView (controls + charts + header)**

Create `web/src/v2/views/HistoryView.tsx`:
- **State:** `histBase` (default `DAILY_DRIVER_BASE`), `histPack` ("group"|"A"|"B"), `histRange` ("all"|"24h"|"7d"|"1m"|"1y"|"custom") + `customFrom`/`customTo`. Persist to `bmsmon-v2-hist` (localStorage). Resolve `[fromMs, toMs]` from the range ("all" → use the base's earliest `first_ms` once known, else now−1y; else now−span).
- **Selected addresses:** from `groupBases(data.items, data.staleAddrs)` → the base's packs; `group` = both, `A`/`B` = that pack. Call `useTrends(addresses, fromMs, toMs)`.
- **Controls row:** base buttons; `Segmented` for Group/A/B; range buttons (Custom reveals two `<input type="date">`).
- **Header:** per-pack install/first-data line from each series' `first_ms` year (e.g. "A since 2024 · B since 2024"; differing years read as replaced).
- **Charts (3 `LineChart`s):**
  - Capacity fade: series = SOH per pack (`var(--ok)`); `bands` for good/fair/degraded (faint `--ok`/`--warn`/`--live`); caption from `projectMonthsTo80(mergedGroupPoints)` → "≈ N mo to 80%" or "insufficient data".
  - Cell imbalance: series = `cell_spread_mv` per pack (`var(--warn)`); `watchLine` at 40 (`var(--live)`).
  - Temperature: full-width; series = `temp_avg` (`var(--text-3)`) + `ribbon` from `temp_min`/`temp_max`; y labels via `formatTemp`.
  - Group mode = averaged single line per §8 (blend the base's packs bucket-by-bucket); A/B = the one/both packs as separate series.
- On `mobile`, charts stack full width; controls wrap.
Signature: `export function HistoryView({ data, unit, mobile }: { data: FleetData; unit: TempUnit; mobile: boolean })`. (Charge-session table + notes are added in Task 8.)

- [ ] **Step 3: Verify**

Run: `cd web && npx tsc -b && npm run build` — Expected: clean + builds. (Runtime drive in Task 9.)

- [ ] **Step 4: Commit**

```bash
git add web/src/v2/views/HistoryView.tsx web/src/v2/useTrends.ts
git commit -m "feat(web): History view — base/AB/range controls + 3 trend charts"
```

---

### Task 8: Charge-session table + Notes + App wiring

**Files:**
- Create: `web/src/v2/components/ChargeSessionTable.tsx`, `web/src/v2/components/NotesCard.tsx`
- Modify: `web/src/v2/views/HistoryView.tsx` (mount both), `web/src/v2/App.tsx` (replace the history placeholder)

**Interfaces:**
- Consumes: `getChargeSessions`/`ChargeSession`, `getNotes`/`putNote`/`NoteRow`, `formatTemp`.
- Produces: `<ChargeSessionTable addresses days unit />`, `<NotesCard baseId />`; History wired into App.

- [ ] **Step 1: ChargeSessionTable**

Create `web/src/v2/components/ChargeSessionTable.tsx`: fetches `getChargeSessions(address)` for each selected address (label A/B in group mode; merge + sort newest-first), renders a table — DATE (`start_ms`) · FROM→100% (`from_soc`%) · DURATION (`duration_min`→"Hh Mm") · CV TAIL (`cv_tail_min`m) · PEAK TEMP (`formatTemp(peak_temp_c, unit)`). Empty → "No completed charge sessions in the last N days." On mobile, horizontal scroll.

```tsx
export function ChargeSessionTable({ addresses, labels, days = 30, unit }: {
  addresses: string[]; labels: Record<string, string>; days?: number; unit: TempUnit;
}) { /* … */ }
```

- [ ] **Step 2: NotesCard (debounced save)**

Create `web/src/v2/components/NotesCard.tsx`: loads the note for `baseId` from `getNotes()` on mount, renders a textarea, and **debounces** (~800 ms) a `putNote(baseId, body)` on edit; shows a small "saving…/saved" indicator. Keeps last text locally; a failed save shows "save failed — retry".

```tsx
export function NotesCard({ baseId }: { baseId: string }) { /* … */ }
```

- [ ] **Step 3: Mount in HistoryView + wire App**

In `HistoryView.tsx`, render `<ChargeSessionTable addresses={addresses} labels={labels} unit={unit} />` and `<NotesCard baseId={histBase} />` below the charts. In `web/src/v2/App.tsx`, replace the `history` branch: `view === "history" ? <HistoryView data={data} unit={settings.tempUnitPref} mobile={mobile} /> :`. Journey stays a placeholder. No other App change.

- [ ] **Step 4: Verify build + drive**

Run: `cd web && npx tsc -b && npm test && npm run build` — Expected: clean + green + both bundles. Then drive `/v2/` against a running backend (dev Postgres + local server): History shows the three charts, controls refetch, the charge-session table lists sessions or its empty state, notes save + reload. If no backend, confirm served HTML + clean build and defer the runtime drive to Task 9.

- [ ] **Step 5: Commit**

```bash
git add web/src/v2/components/ChargeSessionTable.tsx web/src/v2/components/NotesCard.tsx web/src/v2/views/HistoryView.tsx web/src/v2/App.tsx
git commit -m "feat(web): charge-session table + persisted notes; wire History into shell"
```

---

### Task 9: Full suite + end-to-end verification + docs

**Files:** docs only.

- [ ] **Step 1: Web suite + types** — `cd web && npm test && npx tsc -b` (all green + no type errors).
- [ ] **Step 2: Server suite** — `docker compose -f server/docker-compose.dev.yml up -d && cd server && .venv/bin/python -m pytest` (all green, incl. the new trends/charge-sessions/notes tests).
- [ ] **Step 3: Build both bundles** — `cd web && npm run build` (`dist/index.html` + `dist/v2/index.html`).
- [ ] **Step 4: Drive v2 end-to-end (verify skill)** — serve dist (or dev server + proxy against a seeded fleet with a charge ramp + a couple weeks of samples); load `/v2/` → History renders the 3 charts, base/AB/range controls refetch, charge-session table + empty states, notes save→reload; Command/Health/Alerts/Settings + v1 unaffected. Capture evidence.
- [ ] **Step 5: Docs** — update `docs/superpowers/specs/2026-07-12-webui-v2-roadmap.md` progress log (Phase 3 complete) + the `CLAUDE.md` "WebUI v2" subsection (History live; `/web/trends`, `/web/charge-sessions`, `/web/notes` added — the last the first WebUI write path). Commit:

```bash
git add docs/superpowers/specs/2026-07-12-webui-v2-roadmap.md CLAUDE.md
git commit -m "docs: mark WebUI v2 Phase 3 (History) complete"
```

---

## Self-Review (completed)

**Spec coverage:** `/web/trends` → Task 1; `/web/charge-sessions` + detection → Task 2; `web_notes` + GET/POST → Task 3; web types/decoders/api → Task 4; SOH-projection/bands model → Task 5; LineChart → Task 6; History controls + charts + header → Task 7; charge-session table + notes + App wiring → Task 8; verification + docs → Task 9. Every spec §2–§5 item maps to a task.

**Deferred (correctly out of Phase 3):** Journey/GPS; per-cell (C1–C4) history; WS-streamed trends. §8 open items are implementation-time picks.

**Type consistency:** `TrendPoint`/`TrendSeries`/`ChargeSession`/`NoteRow` defined once in `trends.ts` and consumed identically by decoders, api, hooks, and view. Server `detect_charge_sessions` output keys match the web `ChargeSession` fields (`start_ms/end_ms/from_soc/duration_min/cv_tail_min/peak_temp_c`). `trend_series` bucket expression matches `trend_bucket_ms`. `NoteBody{base_id,body}` matches `putNote`'s POST body and the `web_notes` columns.
