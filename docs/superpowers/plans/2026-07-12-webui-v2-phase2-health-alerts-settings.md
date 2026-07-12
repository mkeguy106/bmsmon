# WebUI v2 — Phase 2 (Fleet Health + Alerts + Settings) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn three of v2's placeholder views into live views — Fleet Health, Alerts, Settings — and add one small read-only `GET /web/history` endpoint that feeds the Fleet Health 24H sparkline.

**Architecture:** New views/components/models under `web/src/v2/`, reusing the Phase-1 shell, data layer, `temp.ts`/`fleet.ts` helpers, and `Atoms`. One additive server endpoint (`/web/history`) + query. Alert derivation and the nav unacked-badge are lifted into `App`. No v1 changes.

**Tech Stack:** React 18 + Vite + TypeScript + vitest (web); FastAPI + asyncpg + Postgres 16 + pytest (server).

## Global Constraints

- **No new npm dependencies.** Sparklines/segmented toggles are inline SVG / CSS.
- **v1 is never modified**, and **Phase-1 Command behavior is unchanged.** Shared files (`api.ts`, `decode.ts`, `types.ts`, `web.py`, `queries.py`) may only be extended additively.
- **`/web/history` is read-only, `current_user` (NOT admin), additive** — new route + query only, no schema change.
- **Reuse, don't reimplement:** `temp.ts` (`tempZone`, `zoneCopy`, `thresholdsFromConfig`, `envelopeFromConfig`, `selectActiveConfig`, `formatTemp`, `REDODO_DEFAULTS`), `fleet.ts` (`groupBases`, `isCharging`, `isDischarging`, `deltaMv`, `DAILY_DRIVER_BASE`), `Atoms` (`Bar`, `StatTile`, `Chip`), `api.ts` (`getTempConfig`, `getAlertConfig`), `useV2Settings`.
- **All colors via CSS variables** (tokens). Capacity ladder is the design's fixed `[30,25,20,15,10,5]`, critical `≤15`.
- **Persistence:** settings stay in `bmsmon-v2-settings` (via `useV2Settings`); Alerts acknowledgement is **session-only** (not persisted).
- **Local dev/test:** web `cd web && npm test`; server `cd server && .venv/bin/python -m pytest` (dev DB: `docker compose -f server/docker-compose.dev.yml up -d`).

---

## Part A — Backend history endpoint

### Task 1: `history_series` query + `GET /web/history`

**Files:**
- Modify: `server/app/db/queries.py` (add `history_series`)
- Modify: `server/app/routers/web.py` (add the route)
- Test: `server/tests/test_history.py` (create)

**Interfaces:**
- Produces: `GET /web/history?hours=<int>` → `{"series": [ { "address": str, "points": [ { "t": int, "soc": float }, … ] }, … ] }`. `history_series(conn, since_ms) -> list[dict]` returns flat rows `{address, bucket_ms, soc}` ordered by address, bucket.

- [ ] **Step 1: Write the failing test**

Create `server/tests/test_history.py`, following the fixture pattern in `server/tests/test_cells.py` (the `app` fixture's live pool + manual device/battery insert + `insert_samples`; adapt to the real fixtures as `test_cells.py` did):

```python
import pytest
from app.db import queries as q

pytestmark = pytest.mark.asyncio

BUCKET = 1_800_000

async def test_history_buckets_and_averages(conn, a_device_and_battery):
    device_id, address = a_device_and_battery
    base = 10 * BUCKET  # a clean bucket boundary
    rows = [
        q.sample_row(device_id, address, {"ts_ms": base + 60_000, "soc": 80.0}),
        q.sample_row(device_id, address, {"ts_ms": base + 120_000, "soc": 90.0}),  # same bucket → avg 85
        q.sample_row(device_id, address, {"ts_ms": base + BUCKET + 60_000, "soc": 70.0}),  # next bucket
    ]
    assert await q.insert_samples(conn, rows) == 3
    series = await q.history_series(conn, since_ms=base)
    pts = [(r["bucket_ms"], round(r["soc"])) for r in series if r["address"] == address]
    assert pts == [(base, 85), (base + BUCKET, 70)]


async def test_history_excludes_link_events(conn, a_device_and_battery):
    device_id, address = a_device_and_battery
    base = 20 * BUCKET
    rows = [
        q.sample_row(device_id, address, {"ts_ms": base + 1000, "soc": 50.0}),
        q.sample_row(device_id, address, {"ts_ms": base + 2000, "soc": None, "link_event": "Connected"}),
    ]
    await q.insert_samples(conn, rows)
    series = await q.history_series(conn, since_ms=base)
    assert [round(r["soc"]) for r in series if r["address"] == address] == [50]
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd server && .venv/bin/python -m pytest tests/test_history.py -v`
Expected: FAIL — `history_series` doesn't exist.

- [ ] **Step 3: Implement the query**

In `server/app/db/queries.py`, add:

```python
HISTORY_BUCKET_MS = 1_800_000  # 30-minute buckets


async def history_series(conn, since_ms: int) -> list[dict]:
    """Per-pack 30-minute-bucketed average SOC since since_ms (real telemetry only).

    Returns flat rows {address, bucket_ms, soc} ordered by address, bucket_ms — the
    route groups them into one series per pack. Bounded by the window, not row-capped."""
    rows = await conn.fetch(
        """SELECT address,
                  (ts_ms / $2) * $2 AS bucket_ms,
                  avg(soc)::real AS soc
             FROM samples
            WHERE ts_ms >= $1 AND link_event IS NULL AND soc IS NOT NULL
            GROUP BY address, bucket_ms
            ORDER BY address, bucket_ms""",
        since_ms, HISTORY_BUCKET_MS,
    )
    return [dict(r) for r in rows]
```

- [ ] **Step 4: Add the route**

In `server/app/routers/web.py`, add `import time` at the top (if absent) and the route:

```python
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
```

(`Query` is already imported in web.py; add `import time` if not present.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd server && .venv/bin/python -m pytest tests/test_history.py -v` — Expected: PASS.
Then full suite: `cd server && .venv/bin/python -m pytest` — Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add server/app/db/queries.py server/app/routers/web.py server/tests/test_history.py
git commit -m "feat(server): read-only /web/history endpoint (bucketed per-pack SOC)"
```

---

### Task 2: Web `getHistory` + history decoder

**Files:**
- Modify: `web/src/api.ts` (add `getHistory`)
- Modify: `web/src/decode.ts` (add `decodeHistory`)
- Create: `web/src/v2/history.ts` (types)
- Test: `web/src/decode.test.ts` (add cases)

**Interfaces:**
- Produces: `interface HistPoint { t:number; soc:number }`, `interface HistSeries { address:string; points:HistPoint[] }`; `decodeHistory(x:unknown): HistSeries[] | null`; `getHistory(hours?:number): Promise<{ series: HistSeries[] }>`.

- [ ] **Step 1: Write the failing test**

Add to `web/src/decode.test.ts`:

```ts
import { decodeHistory } from "./decode";

it("decodes a valid history payload", () => {
  const s = decodeHistory([{ address: "AA", points: [{ t: 1000, soc: 85 }, { t: 2000, soc: 70 }] }]);
  expect(s).toEqual([{ address: "AA", points: [{ t: 1000, soc: 85 }, { t: 2000, soc: 70 }] }]);
});

it("drops malformed points but keeps the series", () => {
  const s = decodeHistory([{ address: "AA", points: [{ t: 1000, soc: 85 }, { t: null, soc: 70 }] }]);
  expect(s).toEqual([{ address: "AA", points: [{ t: 1000, soc: 85 }] }]);
});

it("returns null for a non-array", () => {
  expect(decodeHistory({ nope: true })).toBeNull();
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd web && npm test -- decode`
Expected: FAIL — `decodeHistory` not exported.

- [ ] **Step 3: Implement types + decoder**

Create `web/src/v2/history.ts`:

```ts
export interface HistPoint { t: number; soc: number }
export interface HistSeries { address: string; points: HistPoint[] }
```

In `web/src/decode.ts`, add (using the existing `isObj`/`warn` helpers):

```ts
import type { HistSeries } from "./v2/history";

export function decodeHistory(x: unknown): HistSeries[] | null {
  if (!Array.isArray(x)) return warn("history", x);
  const out: HistSeries[] = [];
  for (const s of x) {
    if (!isObj(s) || typeof s.address !== "string" || !Array.isArray(s.points)) continue;
    const points = s.points.filter(
      (p): p is { t: number; soc: number } =>
        isObj(p) && Number.isFinite(p.t) && Number.isFinite(p.soc),
    );
    out.push({ address: s.address, points });
  }
  return out;
}
```

- [ ] **Step 4: Implement `getHistory`**

In `web/src/api.ts`, add:

```ts
import { decodeHistory } from "./decode";
import type { HistSeries } from "./v2/history";

export const getHistory = async (hours = 24): Promise<{ series: HistSeries[] }> => {
  const r = await fetch(`/web/history?hours=${hours}`).then(j);
  const series = isObj(r) ? decodeHistory((r as { series?: unknown }).series) : null;
  if (!series) throw new Error("malformed /web/history response");
  return { series };
};
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd web && npm test -- decode && npx tsc -b` — Expected: PASS + no type errors.

- [ ] **Step 6: Commit**

```bash
git add web/src/api.ts web/src/decode.ts web/src/v2/history.ts web/src/decode.test.ts
git commit -m "feat(web): getHistory client + history decoder"
```

---

## Part B — Fleet Health

### Task 3: `model/health.ts` (pure)

**Files:**
- Create: `web/src/v2/model/health.ts`
- Test: `web/src/v2/model/health.test.ts`

**Interfaces:**
- Produces:
  `interface HealthSummary { ready:number; needRecharge:number; degraded:number; capacityPct:number }`
  `healthSummary(items: FleetItem[], staleAddrs: Set<string>): HealthSummary`
  `healthBoardOrder(items: FleetItem[], staleAddrs: Set<string>): FleetItem[]`
  `type PackStatus = "in-use"|"charging"|"low"|"idle"|"offline"`
  `packStatus(item: FleetItem, connected: boolean): PackStatus`

- [ ] **Step 1: Write the failing test**

Create `web/src/v2/model/health.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { healthSummary, healthBoardOrder, packStatus } from "./health";
import type { FleetItem } from "../../types";

const mk = (o: Partial<FleetItem>): FleetItem => ({ address: "x", ts_ms: 1, ...o });

describe("healthSummary", () => {
  const items = [
    mk({ address: "a", soc: 95, soh: 99, remaining_ah: 95, full_charge_ah: 100 }),
    mk({ address: "b", soc: 20, soh: 99, remaining_ah: 20, full_charge_ah: 100 }),
    mk({ address: "c", soc: 88, soh: 72, remaining_ah: 88, full_charge_ah: 100 }),
    mk({ address: "d", soc: 100, soh: 99, remaining_ah: 100, full_charge_ah: 100 }),
  ];
  it("counts ready/recharge/degraded and fleet capacity, excluding stale from live counts", () => {
    const s = healthSummary(items, new Set(["d"]));
    expect(s.ready).toBe(1);          // a (95); d is stale
    expect(s.needRecharge).toBe(1);   // b (20)
    expect(s.degraded).toBe(1);       // c (soh 72) — degraded counts regardless of stale
    expect(Math.round(s.capacityPct)).toBe(68); // (95+20+88)/(300) over connected a,b,c
  });
  it("capacityPct is 0 when no connected pack has capacity", () => {
    expect(healthSummary([mk({ address: "a" })], new Set()).capacityPct).toBe(0);
  });
});

describe("healthBoardOrder", () => {
  it("puts disconnected first, then ascending SOC", () => {
    const items = [mk({ address: "a", soc: 80 }), mk({ address: "b", soc: 20 }), mk({ address: "c", soc: 50 })];
    const order = healthBoardOrder(items, new Set(["c"])).map((i) => i.address);
    expect(order).toEqual(["c", "b", "a"]); // c disconnected first; then 20, 80
  });
});

describe("packStatus", () => {
  it("classifies", () => {
    expect(packStatus(mk({ current_a: -4, soc: 80 }), true)).toBe("in-use");
    expect(packStatus(mk({ current_a: 5, soc: 80 }), true)).toBe("charging");
    expect(packStatus(mk({ current_a: 0, soc: 20 }), true)).toBe("low");
    expect(packStatus(mk({ current_a: 0, soc: 80 }), true)).toBe("idle");
    expect(packStatus(mk({ soc: 80 }), false)).toBe("offline");
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd web && npm test -- health` — Expected: FAIL (module not found).

- [ ] **Step 3: Implement**

Create `web/src/v2/model/health.ts`:

```ts
import type { FleetItem } from "../../types";
import { isCharging, isDischarging } from "../fleet";

export const READY_SOC = 90;
export const RECHARGE_SOC = 30;
export const DEGRADED_SOH = 80;

export interface HealthSummary { ready: number; needRecharge: number; degraded: number; capacityPct: number }

export function healthSummary(items: FleetItem[], staleAddrs: Set<string>): HealthSummary {
  const live = items.filter((i) => !staleAddrs.has(i.address));
  const ready = live.filter((i) => (i.soc ?? -1) >= READY_SOC).length;
  const needRecharge = live.filter((i) => (i.soc ?? Infinity) < RECHARGE_SOC).length;
  const degraded = items.filter((i) => i.soh != null && i.soh < DEGRADED_SOH).length;
  let rem = 0, full = 0;
  for (const i of live) {
    if (i.remaining_ah != null && i.full_charge_ah != null && i.full_charge_ah > 0) {
      rem += i.remaining_ah; full += i.full_charge_ah;
    }
  }
  return { ready, needRecharge, degraded, capacityPct: full > 0 ? (rem / full) * 100 : 0 };
}

/** Attention-first: disconnected packs first, then ascending SOC (nulls last), stable. */
export function healthBoardOrder(items: FleetItem[], staleAddrs: Set<string>): FleetItem[] {
  return items
    .map((i, idx) => ({ i, idx, off: staleAddrs.has(i.address) }))
    .sort((a, b) => {
      if (a.off !== b.off) return a.off ? -1 : 1;
      const sa = a.i.soc ?? Infinity, sb = b.i.soc ?? Infinity;
      return sa !== sb ? sa - sb : a.idx - b.idx;
    })
    .map((x) => x.i);
}

export type PackStatus = "in-use" | "charging" | "low" | "idle" | "offline";
export function packStatus(item: FleetItem, connected: boolean): PackStatus {
  if (!connected) return "offline";
  if (isDischarging(item)) return "in-use";
  if (isCharging(item)) return "charging";
  if ((item.soc ?? Infinity) < RECHARGE_SOC) return "low";
  return "idle";
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd web && npm test -- health` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web/src/v2/model/health.ts web/src/v2/model/health.test.ts
git commit -m "feat(web): fleet-health summary + board-order + pack-status model"
```

---

### Task 4: Sparkline component + `useHistory` hook

**Files:**
- Create: `web/src/v2/components/Sparkline.tsx`
- Create: `web/src/v2/useHistory.ts`

**Interfaces:**
- Consumes: `HistPoint` (`./history`), `getHistory` (`../api`).
- Produces: `Sparkline({ points, width, height })` (inline SVG polyline, y 0–100); `useHistory(): Map<string, HistPoint[]>` (polls `getHistory(24)` every ~180 s).

- [ ] **Step 1: Implement Sparkline**

Create `web/src/v2/components/Sparkline.tsx`:

```tsx
import type { HistPoint } from "../history";

export function Sparkline({ points, width = 96, height = 26 }: {
  points: HistPoint[] | undefined; width?: number; height?: number;
}) {
  const pts = points ?? [];
  if (pts.length < 2) {
    return <svg width={width} height={height} aria-hidden><line x1={0} y1={height / 2} x2={width}
      y2={height / 2} stroke="var(--track)" strokeWidth={1} /></svg>;
  }
  const t0 = pts[0].t, t1 = pts[pts.length - 1].t, span = t1 - t0 || 1;
  const d = pts.map((p) => {
    const x = ((p.t - t0) / span) * width;
    const y = height - (Math.max(0, Math.min(100, p.soc)) / 100) * height;
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  }).join(" ");
  return <svg width={width} height={height}>
    <polyline points={d} fill="none" stroke="var(--text-3)" strokeWidth={1.5}
      strokeLinejoin="round" strokeLinecap="round" /></svg>;
}
```

- [ ] **Step 2: Implement useHistory**

Create `web/src/v2/useHistory.ts`:

```ts
import { useEffect, useState } from "react";
import { getHistory } from "../api";
import type { HistPoint } from "./history";

const REFRESH_MS = 180_000;

export function useHistory(): Map<string, HistPoint[]> {
  const [map, setMap] = useState<Map<string, HistPoint[]>>(new Map());
  useEffect(() => {
    let alive = true;
    const load = () => getHistory(24)
      .then((r) => { if (alive) setMap(new Map(r.series.map((s) => [s.address, s.points]))); })
      .catch(() => { /* keep last */ });
    load();
    const t = setInterval(load, REFRESH_MS);
    return () => { alive = false; clearInterval(t); };
  }, []);
  return map;
}
```

- [ ] **Step 3: Verify**

Run: `cd web && npx tsc -b` — Expected: clean. (Visual in Task 10.)

- [ ] **Step 4: Commit**

```bash
git add web/src/v2/components/Sparkline.tsx web/src/v2/useHistory.ts
git commit -m "feat(web): SOC sparkline component + history poll hook"
```

---

### Task 5: `HealthView`

**Files:**
- Create: `web/src/v2/views/HealthView.tsx`

**Interfaces:**
- Consumes: `FleetData` (from `useFleetData`), `Map<string,HistPoint[]>`, `healthSummary`/`healthBoardOrder`/`packStatus`, `groupBases`/`DAILY_DRIVER_BASE`, `Bar`/`StatTile`/`Chip`, `Sparkline`, `formatTemp` (`../../temp`).
- Produces: `HealthView({ data, history, unit, mobile })`.

- [ ] **Step 1: Implement**

Create `web/src/v2/views/HealthView.tsx`. Layout per spec §4:
- **4 `StatTile`s** from `healthSummary(data.items, data.staleAddrs)`: `PACKS READY {ready}/8`, `NEED RECHARGE {needRecharge}`, `DEGRADED {degraded}`, `FLEET CAPACITY {round(capacityPct)}%`.
- **"IN USE NOW" hero:** `groupBases(data.items, data.staleAddrs)` → the base with `id === DAILY_DRIVER_BASE` (fallback first base); render its two packs with SOC / capacity (`remaining_ah`/`full_charge_ah`) / health (`soh`) `Bar`s.
- **8-row board** from `healthBoardOrder(data.items, data.staleAddrs)`: columns PACK (alias) · CAPACITY (`Bar` `soc/100` + `%`) · HEALTH (`Bar` `soh/100` + `%`, "—" when null) · TEMP (`formatTemp(temp_c, unit)`) · CYCLES · `<Sparkline points={history.get(address)} />` · STATUS `Chip` (`packStatus(item, !stale)`, color by status). Disconnected rows muted (no %). On `mobile`, wrap the board in a horizontally scrolling container (`overflow-x:auto`, inner `min-width:720px`).
- All colors via tokens. Signature:

```tsx
import type { FleetData } from "../useFleetData";
import type { HistPoint } from "../history";
import type { TempUnit } from "../../temp";
export function HealthView({ data, history, unit, mobile }: {
  data: FleetData; history: Map<string, HistPoint[]>; unit: TempUnit; mobile: boolean;
}) { /* … */ }
```

(If `useFleetData` doesn't already export a `FleetData` type, add `export type FleetData = ReturnType<typeof useFleetData>;` to `useFleetData.ts` in this task.)

- [ ] **Step 2: Verify**

Run: `cd web && npx tsc -b && npm run build` — Expected: clean + builds. (Wired + visually verified in Tasks 9–10.)

- [ ] **Step 3: Commit**

```bash
git add web/src/v2/views/HealthView.tsx web/src/v2/useFleetData.ts
git commit -m "feat(web): v2 Fleet Health view (tiles, in-use hero, 8-pack board + sparklines)"
```

---

## Part C — Alerts

### Task 6: `model/alerts.ts` (pure)

**Files:**
- Create: `web/src/v2/model/alerts.ts`
- Test: `web/src/v2/model/alerts.test.ts`

**Interfaces:**
- Produces:
  `type AlertSeverity = "critical"|"warning"`
  `interface V2Alert { id:string; address:string; severity:AlertSeverity; title:string; msg:string; tsMs:number; kind:"capacity"|"temp"|"cell" }`
  `deriveAlerts(items: FleetItem[], staleAddrs: Set<string>, tempCfg: TempConfig | null): V2Alert[]`
  `CAPACITY_LADDER = [30,25,20,15,10,5]`, `CRITICAL_SOC = 15`.

- [ ] **Step 1: Write the failing test**

Create `web/src/v2/model/alerts.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { deriveAlerts } from "./alerts";
import type { FleetItem } from "../../types";

const mk = (o: Partial<FleetItem>): FleetItem => ({ address: "x", ts_ms: 100, ...o });

describe("deriveAlerts", () => {
  it("fires a warning capacity alert at the crossed rung", () => {
    const a = deriveAlerts([mk({ address: "a", soc: 22 })], new Set(), null);
    const cap = a.find((x) => x.kind === "capacity")!;
    expect(cap.severity).toBe("warning");   // 22 → rung 20, not ≤15
    expect(cap.id).toBe("cap:a");
  });
  it("capacity is critical at/below 15", () => {
    expect(deriveAlerts([mk({ address: "a", soc: 12 })], new Set(), null)
      .find((x) => x.kind === "capacity")!.severity).toBe("critical");
  });
  it("no capacity alert above the top rung", () => {
    expect(deriveAlerts([mk({ address: "a", soc: 40 })], new Set(), null)
      .some((x) => x.kind === "capacity")).toBe(false);
  });
  it("fires a critical temp alert when hot", () => {
    const a = deriveAlerts([mk({ address: "a", soc: 80, temp_c: 55 })], new Set(), null);
    expect(a.find((x) => x.kind === "temp")!.severity).toBe("critical"); // ≥ hotCrit 53
  });
  it("fires a cell-imbalance warning over 40 mV", () => {
    const a = deriveAlerts([mk({ address: "a", soc: 80, cells: [3.30, 3.36, 3.31, 3.32] })], new Set(), null);
    expect(a.find((x) => x.kind === "cell")!.severity).toBe("warning"); // Δ 60 mV → warning (>40, ≤60)
  });
  it("excludes disconnected packs", () => {
    expect(deriveAlerts([mk({ address: "a", soc: 5 })], new Set(["a"]), null)).toHaveLength(0);
  });
  it("sorts critical before warning", () => {
    const a = deriveAlerts([mk({ address: "a", soc: 22 }), mk({ address: "b", soc: 8 })], new Set(), null);
    expect(a[0].severity).toBe("critical");
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd web && npm test -- alerts` — Expected: FAIL (module not found).

- [ ] **Step 3: Implement**

Create `web/src/v2/model/alerts.ts`:

```ts
import type { FleetItem } from "../../types";
import { deltaMv } from "../fleet";
import {
  tempZone, zoneCopy, thresholdsFromConfig, envelopeFromConfig, type TempConfig,
} from "../../temp";

export type AlertSeverity = "critical" | "warning";
export interface V2Alert {
  id: string; address: string; severity: AlertSeverity;
  title: string; msg: string; tsMs: number; kind: "capacity" | "temp" | "cell";
}

export const CAPACITY_LADDER = [30, 25, 20, 15, 10, 5];
export const CRITICAL_SOC = 15;

/** Highest ladder rung the SOC is at/below, or null if above the top rung. */
function crossedRung(soc: number): number | null {
  let hit: number | null = null;
  for (const r of CAPACITY_LADDER) if (soc <= r) hit = hit == null ? r : Math.min(hit, r);
  return hit;
}

export function deriveAlerts(
  items: FleetItem[], staleAddrs: Set<string>, tempCfg: TempConfig | null,
): V2Alert[] {
  const out: V2Alert[] = [];
  const thr = thresholdsFromConfig(tempCfg), env = envelopeFromConfig(tempCfg);
  for (const i of items) {
    if (staleAddrs.has(i.address)) continue;
    // capacity
    if (i.soc != null) {
      const rung = crossedRung(i.soc);
      if (rung != null) {
        const critical = i.soc <= CRITICAL_SOC;
        out.push({ id: `cap:${i.address}`, address: i.address, kind: "capacity",
          severity: critical ? "critical" : "warning",
          title: critical ? "Critically low" : "Low battery",
          msg: `${Math.round(i.soc)}% — recharge soon.`, tsMs: i.ts_ms });
      }
    }
    // temperature (reuse temp.ts)
    if (i.temp_c != null) {
      const z = tempZone(i.temp_c, thr, env);
      if (z.rank >= 1) {
        const c = zoneCopy(z.key, thr, env);
        out.push({ id: `temp:${i.address}`, address: i.address, kind: "temp",
          severity: z.rank >= 3 ? "critical" : "warning", title: c.title, msg: c.msg, tsMs: i.ts_ms });
      }
    }
    // cell imbalance
    const dv = deltaMv(i);
    if (dv != null && dv > 40) {
      out.push({ id: `cell:${i.address}`, address: i.address, kind: "cell",
        severity: dv > 60 ? "critical" : "warning",
        title: "Cell imbalance", msg: `Δ ${Math.round(dv)} mV across cells.`, tsMs: i.ts_ms });
    }
  }
  const rank = (s: AlertSeverity) => (s === "critical" ? 0 : 1);
  return out.sort((a, b) => rank(a.severity) - rank(b.severity) || b.tsMs - a.tsMs);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd web && npm test -- alerts && npx tsc -b` — Expected: PASS + clean.

- [ ] **Step 5: Commit**

```bash
git add web/src/v2/model/alerts.ts web/src/v2/model/alerts.test.ts
git commit -m "feat(web): v2 alert-derivation model (capacity ladder + temp zones + cell imbalance)"
```

---

### Task 7: `useV2Configs` hook + `AlertsView`

**Files:**
- Create: `web/src/v2/useV2Configs.ts`
- Create: `web/src/v2/views/AlertsView.tsx`

**Interfaces:**
- Consumes: `getTempConfig`/`getAlertConfig` (`../api`), `selectActiveConfig`/`TempConfig` (`../temp`), `V2Alert` (`../model/alerts`).
- Produces: `useV2Configs(): { tempConfig: TempConfig | null; alertConfig: AlertConfig }`; `AlertsView({ alerts, acked, onAck, now })`.

- [ ] **Step 1: Implement useV2Configs**

Create `web/src/v2/useV2Configs.ts` — poll temp-config + alert-config every 60 s (mirrors v1 App.tsx):

```ts
import { useEffect, useState } from "react";
import { getTempConfig, getAlertConfig, DEFAULT_ALERT_CONFIG, type AlertConfig } from "../api";
import { selectActiveConfig, type TempConfig } from "../temp";

export function useV2Configs(): { tempConfig: TempConfig | null; alertConfig: AlertConfig } {
  const [tempConfig, setTempConfig] = useState<TempConfig | null>(null);
  const [alertConfig, setAlertConfig] = useState<AlertConfig>(DEFAULT_ALERT_CONFIG);
  useEffect(() => {
    let alive = true;
    const load = () => {
      getTempConfig().then((r) => { if (alive) setTempConfig(selectActiveConfig(r.configs)); }).catch(() => {});
      getAlertConfig().then((c) => { if (alive) setAlertConfig(c); }).catch(() => {});
    };
    load();
    const t = setInterval(load, 60_000);
    return () => { alive = false; clearInterval(t); };
  }, []);
  return { tempConfig, alertConfig };
}
```

- [ ] **Step 2: Implement AlertsView**

Create `web/src/v2/views/AlertsView.tsx`. Per spec §5.2: list `alerts` (already derived + sorted by App), each row a card with a left severity bar (`--live` critical / `--warn` warning), title, msg, relative time (`now - tsMs`), and an **Acknowledge** button. Acked ids (`acked.has(id)`) render dimmed (`opacity:.5`) and sorted last (partition acked to the bottom within the given order). Empty (no alerts) → centered "All clear." Footer restates thresholds (capacity 30/25/20/15/10/5 crit ≤15; temp caution/critical; cell >40 mV). Signature:

```tsx
import type { V2Alert } from "../model/alerts";
export function AlertsView({ alerts, acked, onAck, now }: {
  alerts: V2Alert[]; acked: Set<string>; onAck: (id: string) => void; now: number;
}) { /* … */ }
```

- [ ] **Step 3: Verify**

Run: `cd web && npx tsc -b && npm run build` — Expected: clean + builds.

- [ ] **Step 4: Commit**

```bash
git add web/src/v2/useV2Configs.ts web/src/v2/views/AlertsView.tsx
git commit -m "feat(web): v2 config poll hook + Alerts view"
```

---

## Part D — Settings

### Task 8: `Segmented` atom + `SettingsView`

**Files:**
- Create: `web/src/v2/components/Segmented.tsx`
- Create: `web/src/v2/views/SettingsView.tsx`

**Interfaces:**
- Consumes: `useV2Settings` (`../useV2Settings`).
- Produces: `Segmented<T extends string>({ options, value, onChange })`; `SettingsView()`.

- [ ] **Step 1: Implement Segmented**

Create `web/src/v2/components/Segmented.tsx`:

```tsx
export function Segmented<T extends string>({ options, value, onChange }: {
  options: { value: T; label: string }[]; value: T; onChange: (v: T) => void;
}) {
  return (
    <div style={{ display: "inline-flex", border: "1px solid var(--border)", borderRadius: 7, overflow: "hidden" }}>
      {options.map((o) => {
        const active = o.value === value;
        return (
          <button key={o.value} onClick={() => onChange(o.value)} className="mono"
            style={{ padding: "6px 12px", fontSize: 11, letterSpacing: ".06em", cursor: "pointer",
              border: "none", background: active ? "var(--nav-active)" : "transparent",
              color: active ? "var(--text)" : "var(--text-3)" }}>
            {o.label}
          </button>
        );
      })}
    </div>
  );
}
```

- [ ] **Step 2: Implement SettingsView**

Create `web/src/v2/views/SettingsView.tsx` — reads `[settings, patch] = useV2Settings()`; a max-width column of `.card`s, each with an `.eyebrow` label + a `Segmented`:
- **UNITS:** Distance `Segmented` `mi`/`km` → `patch({distUnit})`; Temperature `°F`/`°C` → `patch({tempUnitPref})`.
- **JOURNEY MAP:** trail color `DISCHARGE`(power)/`SOC`(soc) → `patch({mapMetricPref})`.
- **APPEARANCE:** Theme `SYSTEM`/`LIGHT`/`DARK` → `patch({themeMode})`.
- **ABOUT:** static text card — `8 packs · 4 bases`, `Redodo 12V 100Ah LiFePO4 · Beken BK-BLE-1.0`, `bmsmon.covert.life`, `WebUI v2`. (Copy from `CLAUDE.md` hardware table.)

```tsx
import { useV2Settings } from "../useV2Settings";
import { Segmented } from "../components/Segmented";
export function SettingsView() { /* … cards … */ }
```

- [ ] **Step 3: Verify**

Run: `cd web && npx tsc -b && npm run build` — Expected: clean + builds.

- [ ] **Step 4: Commit**

```bash
git add web/src/v2/components/Segmented.tsx web/src/v2/views/SettingsView.tsx
git commit -m "feat(web): v2 Settings view + Segmented toggle atom"
```

---

## Part E — Wiring + minor sweep

### Task 9: Wire the three views into `App`, badge, and minor sweep

**Files:**
- Modify: `web/src/v2/App.tsx`
- Modify: `web/src/v2/components/TopBar.tsx` (aria-label sweep)
- Modify: `web/src/v2/tokens.css` (`--badge-text`), `web/src/v2/components/Nav.tsx` + `web/src/v2/components/BottomTabs.tsx` (use the token)

**Interfaces:**
- Consumes: `useV2Configs`, `useHistory`, `deriveAlerts`, `HealthView`/`AlertsView`/`SettingsView`.
- Produces: the three live views wired; `unackedCount` fed to `Nav` + `BottomTabs`.

- [ ] **Step 1: Replace App wiring**

Edit `web/src/v2/App.tsx`: add imports, call the hooks once, derive alerts + hold session `acked`, feed `unackedCount` to both `Nav` and `BottomTabs`, and swap the three placeholders. New body (keep the codecs/theme/device/mobile from the current file):

```tsx
import { useCallback, useMemo, useState } from "react";
// … existing imports …
import { HealthView } from "./views/HealthView";
import { AlertsView } from "./views/AlertsView";
import { SettingsView } from "./views/SettingsView";
import { useV2Configs } from "./useV2Configs";
import { useHistory } from "./useHistory";
import { deriveAlerts } from "./model/alerts";

// inside App(), after `const data = useFleetData();` and `const tempF = …`:
  const { tempConfig } = useV2Configs();
  const history = useHistory();
  const alerts = useMemo(
    () => deriveAlerts(data.items, data.staleAddrs, tempConfig),
    [data.items, data.staleAddrs, tempConfig],
  );
  const [acked, setAcked] = useState<Set<string>>(new Set());
  const ack = useCallback((id: string) => setAcked((p) => new Set(p).add(id)), []);
  const unacked = alerts.filter((a) => !acked.has(a.id)).length;

  const content =
    view === "command" ? <CommandView data={data} mobile={mobile} onOpen={setView} tempF={tempF} /> :
    view === "health" ? <HealthView data={data} history={history} unit={settings.tempUnitPref} mobile={mobile} /> :
    view === "journey" ? <Placeholder title="JOURNEY" /> :
    view === "history" ? <Placeholder title="HISTORY" /> :
    view === "alerts" ? <AlertsView alerts={alerts} acked={acked} onAck={ack} now={data.now} /> :
    <SettingsView />;
```

Then pass `unackedCount={unacked}` to both `<Nav …>` and `<BottomTabs …>` (replacing the two `unacked` references, which are now real). Remove the old `const unacked = 0;` line.

- [ ] **Step 2: Minor sweep — aria-label + badge token**

- In `web/src/v2/tokens.css`, add `--badge-text:#fff;` to BOTH `:root[data-theme="dark"]` and `:root[data-theme="light"]` blocks (white reads on `--live` red in both themes).
- In `web/src/v2/components/Nav.tsx` and `web/src/v2/components/BottomTabs.tsx`, replace the badge text `color: "#fff"` with `color: "var(--badge-text)"`.
- In `web/src/v2/components/TopBar.tsx`, add `aria-label={`Theme: ${themeStep.label}`}` to the theme-cycle button.

- [ ] **Step 3: Verify build + drive it**

Run: `cd web && npx tsc -b && npm test && npm run build` — Expected: clean + all vitest green + both bundles.
Then drive `/v2/` against a running backend (dev Postgres + local server, or the vite proxy): Fleet Health board renders with sparklines; Alerts lists derived alerts and the nav badge shows the unacked count; acknowledging dims the row + decrements the badge; Settings toggles persist and the theme control mirrors the top-bar cycle; Command + v1 unaffected. If no backend is reachable, confirm the served HTML + clean build and note runtime drive is in Task 10.

- [ ] **Step 4: Commit**

```bash
git add web/src/v2/App.tsx web/src/v2/tokens.css web/src/v2/components/Nav.tsx web/src/v2/components/BottomTabs.tsx web/src/v2/components/TopBar.tsx
git commit -m "feat(web): wire Fleet Health / Alerts / Settings into v2 shell + nav badge"
```

---

### Task 10: Full suite + end-to-end verification + docs

**Files:** docs only.

- [ ] **Step 1: Web suite + types**
Run: `cd web && npm test && npx tsc -b` — Expected: all green + no type errors.

- [ ] **Step 2: Server suite**
Run: `docker compose -f server/docker-compose.dev.yml up -d && cd server && .venv/bin/python -m pytest` — Expected: all green (incl. `test_history.py`).

- [ ] **Step 3: Build both bundles**
Run: `cd web && npm run build` — Expected: `dist/index.html` (v1) + `dist/v2/index.html` (v2).

- [ ] **Step 4: Drive v2 end-to-end (verify skill)**
Serve `dist` (or run the dev server + proxy against a seeded fleet), load `/v2/`: Health / Alerts / Settings render and behave per spec; nav badge counts unacked; Command + v1 unaffected. Capture evidence.

- [ ] **Step 5: Docs**
Update `docs/superpowers/specs/2026-07-12-webui-v2-roadmap.md` progress log (Phase 2 complete) and the `CLAUDE.md` "WebUI v2" subsection (Fleet Health/Alerts/Settings now live; `/web/history` endpoint added). Commit:

```bash
git add docs/superpowers/specs/2026-07-12-webui-v2-roadmap.md CLAUDE.md
git commit -m "docs: mark WebUI v2 Phase 2 (Fleet Health + Alerts + Settings) complete"
```

---

## Self-Review (completed)

**Spec coverage:** `/web/history` endpoint → Task 1; web client/decoder → Task 2; Fleet Health model → Task 3, sparkline/hook → Task 4, view → Task 5; Alerts model → Task 6, config hook + view → Task 7; Settings + Segmented → Task 8; App wiring + badge + Phase-1 minor sweep (aria-label, `--badge-text`) → Task 9; verification + docs → Task 10. Every spec §2–§7 item maps to a task.

**Deferred (correctly out of Phase 2):** Journey/History views, real `synced` signal, 1s-tick optimization, persisted acknowledgement.

**Type consistency:** `HistPoint`/`HistSeries`, `HealthSummary`/`PackStatus`, `V2Alert`/`AlertSeverity`, `TempConfig`/`AlertConfig` are defined once and consumed with identical signatures. `deriveAlerts(items, staleAddrs, tempCfg)` and `healthSummary(items, staleAddrs)` signatures match their call sites in `App`/views. Alert ordering uses the local `rank` helper (critical before warning, then most-recent).
