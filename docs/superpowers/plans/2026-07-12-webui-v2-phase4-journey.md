# WebUI v2 — Phase 4 (Journey / GPS) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Journey live — a Leaflet base map with a discharge-colored GPS trail (grey dashed transit legs, hotspots), DAY/RANGE date nav, a playback scrubber with live readouts, and an energy-over-distance chart — backed by a new `GET /web/track` endpoint. This completes all six v2 views.

**Architecture:** Additive `track_series` query + `/web/track` route; new web data layer (`track.ts` types, `decodeTrack`, `getTrack`); a pure, tested `model/journey.ts` (distance, segment classification, hotspots, color bucketing, energy series, base-pack merge); a vanilla-Leaflet `JourneyMap` (one new npm dep) with CARTO dark/light tiles; an `EnergyDistanceChart`; and `JourneyView` wired into the shell. No v1 or Phase-1/2/3 behavior changes.

**Tech Stack:** React 18 + Vite + TypeScript + vitest + **leaflet** (new dep) (web); FastAPI + asyncpg + Postgres 16 + pytest (server).

## Global Constraints

- **One new npm dependency, `leaflet`** (+ `@types/leaflet` dev). NO `react-leaflet`, no other new deps. Use vanilla Leaflet imperatively.
- **v1 is never modified**, and Phases 1–3 behavior is unchanged. Shared files (`api.ts`, `decode.ts`, `web.py`, `queries.py`, `App.tsx`) may only be extended additively.
- **`/web/track` is read-only, `current_user`** (NOT admin), additive — new route + query only, no schema change.
- **Reuse, don't reimplement:** `groupBases`/`DAILY_DRIVER_BASE` (`fleet.ts`), `Ring` (`components/Ring.tsx`), `Segmented`, `useTheme` (returns the resolved `"dark"|"light"`), the existing decoder style. `samples.lat/lon` are `double precision`.
- **All app-chrome colors via CSS variables**; trail/hotspot colors come from the model (`var(--ok)`/`--warn`/`--live`). Tiles are theme-matched CARTO raster (no API key).
- **Schema:** no change (GPS columns already exist from the GPS-telemetry feature).
- **Local dev/test:** web `cd web && npm test`; server `cd server && .venv/bin/python -m pytest` (dev DB: `docker compose -f server/docker-compose.dev.yml up -d`).

---

## Part A — Backend

### Task 1: `GET /web/track`

**Files:**
- Modify: `server/app/db/queries.py` (`track_series`), `server/app/routers/web.py` (route)
- Test: `server/tests/test_track.py` (create), `server/tests/test_web_track.py` (create)

**Interfaces:**
- Produces: `GET /web/track?address=&from_ms=&to_ms=` → `{ address, points:[{t,lat,lon,power_w,current_a,soc}] }`. `track_series(conn, address, from_ms, to_ms)->list[dict]` (15 s buckets, GPS-only).

- [ ] **Step 1: Write the failing tests**

Create `server/tests/test_track.py` (unit — adapt fixtures from `test_cells.py`):

```python
import pytest
from app.db import queries as q

pytestmark = pytest.mark.asyncio

async def test_track_series_buckets_and_filters_gps(conn, a_device_and_battery):
    device_id, address = a_device_and_battery
    bucket = 15_000
    base = 100 * bucket
    rows = [
        q.sample_row(device_id, address, {"ts_ms": base + 1000, "lat": 43.0, "lon": -87.9,
                                          "power_w": -60.0, "current_a": -4.0, "soc": 88}),
        q.sample_row(device_id, address, {"ts_ms": base + 2000, "lat": 43.0002, "lon": -87.9,
                                          "power_w": -80.0, "current_a": -5.0, "soc": 88}),
        q.sample_row(device_id, address, {"ts_ms": base + 3000, "soc": 88}),  # indoor: no lat/lon → excluded
    ]
    await q.insert_samples(conn, rows)
    pts = await q.track_series(conn, address, base, base + bucket)
    assert len(pts) == 1
    assert round(pts[0]["lat"], 4) == 43.0001
    assert round(pts[0]["power_w"]) == -70
```

Create `server/tests/test_web_track.py` mirroring `test_web_history.py` (401 without identity + seeded round-trip asserting `{address, points:[{t,lat,lon,power_w,current_a,soc}]}`).

- [ ] **Step 2: Run to verify they fail**

Run: `cd server && .venv/bin/python -m pytest tests/test_track.py tests/test_web_track.py -v` — Expected: FAIL.

- [ ] **Step 3: Implement the query**

In `server/app/db/queries.py`:

```python
async def track_series(conn, address: str, from_ms: int, to_ms: int) -> list[dict]:
    """15-second buckets of GPS-carrying real telemetry (lat/lon present) with discharge context."""
    rows = await conn.fetch(
        """SELECT (ts_ms / 15000) * 15000 AS bucket_ms,
                  avg(lat)::double precision AS lat, avg(lon)::double precision AS lon,
                  avg(power_w)::real AS power_w, avg(current_a)::real AS current_a, avg(soc)::real AS soc
             FROM samples
            WHERE address = $1 AND ts_ms >= $2 AND ts_ms < $3
              AND link_event IS NULL AND lat IS NOT NULL AND lon IS NOT NULL
            GROUP BY bucket_ms ORDER BY bucket_ms""",
        address, from_ms, to_ms,
    )
    return [dict(r) for r in rows]
```

- [ ] **Step 4: Add the route**

In `server/app/routers/web.py` (reuse the `_f` helper from Phase 3):

```python
@router.get("/track")
async def track(address: str, from_ms: int = Query(...), to_ms: int = Query(...),
                user: AuthUser = Depends(current_user), pool=Depends(get_pool)):
    async with pool.acquire() as conn:
        rows = await q.track_series(conn, address, from_ms, to_ms)
    points = [{"t": int(r["bucket_ms"]), "lat": _f(r["lat"]), "lon": _f(r["lon"]),
               "power_w": _f(r["power_w"]), "current_a": _f(r["current_a"]), "soc": _f(r["soc"])}
              for r in rows]
    return {"address": address, "points": points}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd server && .venv/bin/python -m pytest tests/test_track.py tests/test_web_track.py -v` — PASS. Then full suite green.

- [ ] **Step 6: Commit**

```bash
git add server/app/db/queries.py server/app/routers/web.py server/tests/test_track.py server/tests/test_web_track.py
git commit -m "feat(server): /web/track 15s-bucketed GPS + discharge series"
```

---

## Part B — Web data layer + pure model

### Task 2: `track.ts` types + decoder + api

**Files:**
- Create: `web/src/v2/track.ts`
- Modify: `web/src/decode.ts` (`decodeTrack`), `web/src/api.ts` (`getTrack`)
- Test: `web/src/decode.test.ts` (add cases)

**Interfaces:**
- Produces: `TrackPoint { t; lat; lon; power_w; current_a; soc }` (t/lat/lon required numbers; the rest nullable), `Track { address; points: TrackPoint[] }`; `decodeTrack(x): Track | null`; `getTrack(address, fromMs, toMs): Promise<Track>`.

- [ ] **Step 1: Write the failing tests**

Add to `web/src/decode.test.ts`:

```ts
import { decodeTrack } from "./decode";

it("decodes a track, dropping points missing lat/lon", () => {
  const t = decodeTrack({ address: "AA", points: [
    { t: 1, lat: 43.0, lon: -87.9, power_w: -60, current_a: -4, soc: 88 },
    { t: 2, lat: null, lon: -87.9, power_w: -60, current_a: -4, soc: 88 } ] });
  expect(t?.points.length).toBe(1);
  expect(t?.points[0].lat).toBe(43.0);
});
it("decodeTrack null for malformed root", () => {
  expect(decodeTrack({ address: 5 })).toBeNull();
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd web && npm test -- decode` — Expected: FAIL.

- [ ] **Step 3: Implement**

Create `web/src/v2/track.ts`:

```ts
export interface TrackPoint {
  t: number; lat: number; lon: number;
  power_w: number | null; current_a: number | null; soc: number | null;
}
export interface Track { address: string; points: TrackPoint[] }
```

In `web/src/decode.ts` (reuse `isObj`/`warn`/`numOrNull`):

```ts
import type { Track } from "./v2/track";

export function decodeTrack(x: unknown): Track | null {
  if (!isObj(x) || typeof x.address !== "string" || !Array.isArray(x.points)) return warn("track", x);
  const points = [];
  for (const p of x.points) {
    if (!isObj(p) || !Number.isFinite(p.t) || !Number.isFinite(p.lat) || !Number.isFinite(p.lon)) continue;
    points.push({ t: p.t as number, lat: p.lat as number, lon: p.lon as number,
      power_w: numOrNull(p.power_w) ?? null, current_a: numOrNull(p.current_a) ?? null,
      soc: numOrNull(p.soc) ?? null });
  }
  return { address: x.address, points };
}
```

In `web/src/api.ts`:

```ts
import { decodeTrack } from "./decode";
import type { Track } from "./v2/track";

export const getTrack = async (address: string, fromMs: number, toMs: number): Promise<Track> => {
  const r = await fetch(`/web/track?address=${encodeURIComponent(address)}&from_ms=${fromMs}&to_ms=${toMs}`).then(j);
  const t = decodeTrack(r);
  if (!t) throw new Error("malformed /web/track response");
  return t;
};
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd web && npm test -- decode && npx tsc -b` — PASS + clean.

- [ ] **Step 5: Commit**

```bash
git add web/src/v2/track.ts web/src/decode.ts web/src/api.ts web/src/decode.test.ts
git commit -m "feat(web): track types, decoder, and api client"
```

---

### Task 3: pure `model/journey.ts` (distance, classification, hotspots, energy, merge)

**Files:**
- Create: `web/src/v2/model/journey.ts`
- Test: `web/src/v2/model/journey.test.ts`

**Interfaces:**
- Produces: `haversineMi`, `classifySegment`, `SegKind`, `dischargeColor`, `cumulativeMiles`, `detectHotspots`/`Hotspot`, `energySeries`/`EnergyPoint`, `tripSummary`/`TripSummary`, `mergeBaseTracks`, and the tuning constants.

- [ ] **Step 1: Write the failing tests**

Create `web/src/v2/model/journey.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import {
  haversineMi, classifySegment, dischargeColor, cumulativeMiles,
  detectHotspots, energySeries, tripSummary, mergeBaseTracks,
} from "./journey";
import type { TrackPoint, Track } from "../track";

const p = (o: Partial<TrackPoint>): TrackPoint =>
  ({ t: 0, lat: 43, lon: -87.9, power_w: 0, current_a: 0, soc: 88, ...o });

describe("haversineMi", () => {
  it("~69 mi per degree of longitude at the equator", () => {
    expect(haversineMi({ lat: 0, lon: 0 }, { lat: 0, lon: 1 })).toBeCloseTo(69.09, 1);
  });
  it("zero for identical points", () => {
    expect(haversineMi({ lat: 43, lon: -87 }, { lat: 43, lon: -87 })).toBe(0);
  });
});

describe("classifySegment", () => {
  it("active when discharging", () => expect(classifySegment(p({ current_a: -4 }), 0.01)).toBe("active"));
  it("transit when idle and moved", () => expect(classifySegment(p({ current_a: 0 }), 0.05)).toBe("transit"));
  it("idle when stationary", () => expect(classifySegment(p({ current_a: 0 }), 0)).toBe("idle"));
});

describe("dischargeColor", () => {
  it("green/amber/red by |power|", () => {
    expect(dischargeColor(-50)).toBe("var(--ok)");
    expect(dischargeColor(-200)).toBe("var(--warn)");
    expect(dischargeColor(-400)).toBe("var(--live)");
  });
});

describe("cumulativeMiles + tripSummary", () => {
  it("accumulates distance and sums active miles", () => {
    const pts = [p({ lon: -87.9, current_a: -4 }), p({ lon: -87.89, current_a: -4 })];
    const cum = cumulativeMiles(pts);
    expect(cum[0]).toBe(0);
    expect(cum[1]).toBeGreaterThan(0);
    const s = tripSummary(pts, cum);
    expect(s.activeMiles).toBeCloseTo(cum[1], 5);
    expect(s.peakW).toBe(0); // power_w is 0 here
  });
});

describe("detectHotspots", () => {
  it("finds an interior |power| maximum above threshold", () => {
    const pts = [p({ power_w: -100 }), p({ power_w: -400, lon: -87.8 }), p({ power_w: -100, lon: -87.7 })];
    const cum = cumulativeMiles(pts);
    const hs = detectHotspots(pts, cum, { thresholdW: 330, minGapMi: 0.1 });
    expect(hs.map((h) => h.index)).toEqual([1]);
  });
});

describe("mergeBaseTracks", () => {
  it("sums power/current across packs at aligned t, means coords, min soc", () => {
    const a: Track = { address: "A", points: [p({ t: 100, power_w: -30, current_a: -2, soc: 90 })] };
    const b: Track = { address: "B", points: [p({ t: 100, power_w: -40, current_a: -3, soc: 85 })] };
    const merged = mergeBaseTracks([a, b]);
    expect(merged).toHaveLength(1);
    expect(merged[0].power_w).toBe(-70);
    expect(merged[0].current_a).toBe(-5);
    expect(merged[0].soc).toBe(85);
  });
});

describe("energySeries", () => {
  it("flags transit legs", () => {
    const pts = [p({ current_a: 0, lon: -87.9 }), p({ current_a: 0, lon: -87.8 })];
    const es = energySeries(pts, cumulativeMiles(pts));
    expect(es[1].transit).toBe(true);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd web && npm test -- journey` — Expected: FAIL.

- [ ] **Step 3: Implement**

Create `web/src/v2/model/journey.ts`:

```ts
import type { TrackPoint, Track } from "../track";

export interface LatLon { lat: number; lon: number }

export const DISCHARGE_EPS = 0.1;   // A — matches fleet.ts/android
export const MOVE_EPS_MI = 0.003;   // ~5 m
export const POWER_GREEN_W = 150;   // base-total; below → green
export const POWER_RED_W = 350;     // base-total; at/above → red
export const HOTSPOT_W = 330;       // base-total local-max threshold
export const HOTSPOT_MIN_GAP_MI = 0.1;

const R_MI = 3958.7613;
export function haversineMi(a: LatLon, b: LatLon): number {
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat), dLon = toRad(b.lon - a.lon);
  const s = Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * R_MI * Math.asin(Math.min(1, Math.sqrt(s)));
}

export type SegKind = "active" | "transit" | "idle";
/** Segment kind from the destination point's base current + how far it moved. */
export function classifySegment(cur: TrackPoint, movedMi: number): SegKind {
  const a = cur.current_a ?? 0;
  if (a < -DISCHARGE_EPS) return "active";
  if (Math.abs(a) <= DISCHARGE_EPS && movedMi > MOVE_EPS_MI) return "transit";
  return "idle";
}

export function dischargeColor(powerW: number): string {
  const p = Math.abs(powerW);
  if (p >= POWER_RED_W) return "var(--live)";
  if (p >= POWER_GREEN_W) return "var(--warn)";
  return "var(--ok)";
}

export function cumulativeMiles(points: TrackPoint[]): number[] {
  const out: number[] = [];
  let acc = 0;
  for (let i = 0; i < points.length; i++) {
    if (i > 0) acc += haversineMi(points[i - 1], points[i]);
    out.push(acc);
  }
  return out;
}

export interface Hotspot { index: number; powerW: number }
export function detectHotspots(
  points: TrackPoint[], cumMi: number[],
  { thresholdW = HOTSPOT_W, minGapMi = HOTSPOT_MIN_GAP_MI }: { thresholdW?: number; minGapMi?: number } = {},
): Hotspot[] {
  const cand: Hotspot[] = [];
  for (let i = 1; i < points.length - 1; i++) {
    const p = Math.abs(points[i].power_w ?? 0);
    if (p < thresholdW) continue;
    if (p >= Math.abs(points[i - 1].power_w ?? 0) && p >= Math.abs(points[i + 1].power_w ?? 0))
      cand.push({ index: i, powerW: p });
  }
  cand.sort((a, b) => b.powerW - a.powerW);
  const kept: Hotspot[] = [];
  for (const c of cand) {
    if (kept.every((k) => Math.abs(cumMi[k.index] - cumMi[c.index]) >= minGapMi)) kept.push(c);
  }
  return kept.sort((a, b) => a.index - b.index);
}

export interface EnergyPoint { d: number; power: number; transit: boolean }
export function energySeries(points: TrackPoint[], cumMi: number[]): EnergyPoint[] {
  return points.map((pt, i) => {
    const moved = i > 0 ? haversineMi(points[i - 1], pt) : 0;
    return { d: cumMi[i], power: Math.abs(pt.power_w ?? 0), transit: classifySegment(pt, moved) === "transit" };
  });
}

export interface TripSummary { miles: number; activeMiles: number; transitMiles: number; peakW: number; durationMin: number }
export function tripSummary(points: TrackPoint[], cumMi: number[]): TripSummary {
  let activeMiles = 0, transitMiles = 0, peakW = 0;
  for (let i = 1; i < points.length; i++) {
    const seg = haversineMi(points[i - 1], points[i]);
    const kind = classifySegment(points[i], seg);
    if (kind === "active") activeMiles += seg;
    else if (kind === "transit") transitMiles += seg;
    peakW = Math.max(peakW, Math.abs(points[i].power_w ?? 0));
  }
  return { miles: cumMi[cumMi.length - 1] ?? 0, activeMiles, transitMiles, peakW,
    durationMin: points.length > 1 ? (points[points.length - 1].t - points[0].t) / 60000 : 0 };
}

/** Align the base's packs by bucket t; sum power+current, mean coords, min soc → the chair track. */
export function mergeBaseTracks(tracks: Track[]): TrackPoint[] {
  const byT = new Map<number, TrackPoint[]>();
  for (const tr of tracks) for (const p of tr.points) {
    const arr = byT.get(p.t) ?? byT.set(p.t, []).get(p.t)!;
    arr.push(p);
  }
  const out: TrackPoint[] = [];
  for (const t of [...byT.keys()].sort((a, b) => a - b)) {
    const ps = byT.get(t)!;
    const sum = (f: (p: TrackPoint) => number | null) =>
      ps.reduce((acc, p) => acc + (f(p) ?? 0), 0);
    const mean = (f: (p: TrackPoint) => number | null) => {
      const vs = ps.map(f).filter((v): v is number => v != null && Number.isFinite(v));
      return vs.length ? vs.reduce((a, b) => a + b, 0) / vs.length : 0;
    };
    const socs = ps.map((p) => p.soc).filter((v): v is number => v != null);
    out.push({ t, lat: mean((p) => p.lat), lon: mean((p) => p.lon),
      power_w: sum((p) => p.power_w), current_a: sum((p) => p.current_a),
      soc: socs.length ? Math.min(...socs) : null });
  }
  return out;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd web && npm test -- journey && npx tsc -b` — PASS + clean.

- [ ] **Step 5: Commit**

```bash
git add web/src/v2/model/journey.ts web/src/v2/model/journey.test.ts
git commit -m "feat(web): pure journey model (distance, seg classification, hotspots, energy, base merge)"
```

---

## Part C — Leaflet map + chart + hook

### Task 4: Add leaflet + `JourneyMap` component

**Files:**
- Modify: `web/package.json` (add `leaflet` + `@types/leaflet`)
- Create: `web/src/v2/components/JourneyMap.tsx`

**Interfaces:**
- Consumes: `TrackPoint`, `SegKind`/`Hotspot`/`dischargeColor` (journey model).
- Produces: `JourneyMap({ points, segKinds, hotspots, cursorIndex, theme })` — vanilla-Leaflet map.

- [ ] **Step 1: Add the dependency**

Run: `cd web && npm install leaflet@^1.9.4 && npm install -D @types/leaflet@^1.9.12`
Verify `package.json` + `package-lock.json` updated; `cd web && npm test` still green (no import yet).

- [ ] **Step 2: Implement JourneyMap**

Create `web/src/v2/components/JourneyMap.tsx`. Prose spec:
- `import L from "leaflet"; import "leaflet/dist/leaflet.css";`
- A `useRef` div for the map container (`height: 100%`, min-height ~360 desktop). In a `useEffect([])`,
  create `L.map(el, { zoomControl: true, attributionControl: true })`; keep the map instance in a ref.
- **Tiles by theme:** a helper `tileUrl(theme)` → CARTO `light_all`/`dark_all`
  (`https://{s}.basemaps.cartocdn.com/{light_all|dark_all}/{z}/{x}/{y}{r}.png`, attribution
  `"© OpenStreetMap contributors © CARTO"`). Keep the `L.tileLayer` in a ref; on `theme` change,
  `removeLayer` the old + add the new (a separate `useEffect([theme])`).
- **Trail:** in a `useEffect` keyed on `[points, segKinds]`, clear the previous trail layer group and
  draw one `L.polyline([prev,cur])` per segment: `active` → `{ color: dischargeColor(cur.power_w), weight: 4 }`,
  `transit` → `{ color: "#8a8a8a"/*or read --text-4*/, weight: 3, dashArray: "4 6" }`, `idle` → skipped.
  Add hotspot `L.circleMarker` (red halo: a larger faint `--live` circle + a solid dot) with a tooltip
  `HOTSPOT ${Math.round(powerW)}W`. Fit `map.fitBounds(latLngBounds)` to the trail (guard empty).
- **Cursor:** a `useEffect([cursorIndex])` moves a single `L.circleMarker` to `points[cursorIndex]`.
- **Empty state:** when `points.length === 0`, render a muted centered "No GPS trip recorded" div
  instead of initializing the map (or over it) — no crash.
- Cleanup: `map.remove()` on unmount.
- Colors: trail/hotspot from the model/tokens; map chrome is Leaflet's own. (Leaflet reads CSS var
  colors fine when passed as computed strings; if a CSS var doesn't resolve in an SVG stroke, resolve
  it via `getComputedStyle(document.documentElement).getPropertyValue("--live")` in a small helper.)

```tsx
import type { TrackPoint } from "../track";
import type { SegKind, Hotspot } from "../model/journey";
export function JourneyMap({ points, segKinds, hotspots, cursorIndex, theme }: {
  points: TrackPoint[]; segKinds: SegKind[]; hotspots: Hotspot[]; cursorIndex: number; theme: "dark" | "light";
}) { /* … vanilla Leaflet … */ }
```

- [ ] **Step 3: Verify**

Run: `cd web && npx tsc -b && npm run build` — clean + builds (leaflet + its CSS bundled into the v2 chunk). Check the v2 bundle grew by ~40 KB and v1 is unaffected.

- [ ] **Step 4: Commit**

```bash
git add web/package.json web/package-lock.json web/src/v2/components/JourneyMap.tsx
git commit -m "feat(web): Leaflet JourneyMap — CARTO theme tiles, discharge trail, hotspots, cursor"
```

---

### Task 5: `EnergyDistanceChart` + `useTrack` hook

**Files:**
- Create: `web/src/v2/components/EnergyDistanceChart.tsx`, `web/src/v2/useTrack.ts`

**Interfaces:**
- Consumes: `EnergyPoint` (journey model), `getTrack`/`Track`, `mergeBaseTracks`.
- Produces: `EnergyDistanceChart({ energy, cursorIndex, distUnit })`; `useTrack(addresses, fromMs, toMs): TrackPoint[]` (the merged chair track).

- [ ] **Step 1: Implement useTrack**

Create `web/src/v2/useTrack.ts`: fetch `getTrack` for each address in parallel (`alive` guard, per-address `.catch(()=>null)`), `mergeBaseTracks(tracks.filter(Boolean))` → `TrackPoint[]`; refetch keyed on `addresses.join(",")+fromMs+toMs`.

- [ ] **Step 2: Implement EnergyDistanceChart**

Create `web/src/v2/components/EnergyDistanceChart.tsx` — inline-SVG (x = cumulative distance `d`,
y = `power`): a `--power`/`--warn` line, faint shaded rects over contiguous `transit` legs with an
"IN TRANSIT · BATTERY IDLE" label, a vertical cursor line at `energy[cursorIndex].d`, x labels in
`distUnit` (mi/km), y in W. Empty/insufficient → muted "not enough data". Token-styled.

```tsx
import type { EnergyPoint } from "../model/journey";
export function EnergyDistanceChart({ energy, cursorIndex, distUnit }: {
  energy: EnergyPoint[]; cursorIndex: number; distUnit: "mi" | "km";
}) { /* … */ }
```

- [ ] **Step 3: Verify**

Run: `cd web && npx tsc -b && npm run build && npm test` — clean + builds + green.

- [ ] **Step 4: Commit**

```bash
git add web/src/v2/components/EnergyDistanceChart.tsx web/src/v2/useTrack.ts
git commit -m "feat(web): energy-over-distance chart + merged-track fetch hook"
```

---

## Part D — View + wiring

### Task 6: `JourneyView` — date nav, map, dock, playback, energy chart

**Files:**
- Create: `web/src/v2/views/JourneyView.tsx`

**Interfaces:**
- Consumes: `FleetData`, `groupBases`/`DAILY_DRIVER_BASE`, `useTrack`, journey model (`cumulativeMiles`/`classifySegment`/`detectHotspots`/`energySeries`/`tripSummary`/`dischargeColor`), `JourneyMap`, `EnergyDistanceChart`, `Ring`, `Segmented`, `useLocalStorage`.
- Produces: `JourneyView({ data, theme, unit, mobile })`.

- [ ] **Step 1: Implement**

Create `web/src/v2/views/JourneyView.tsx`. Prose spec:
- **State** (persist `bmsmon-v2-journey`): `dateMode` ("day"|"range"), `day` (date string, default today), `from`/`to` (range). Resolve `[fromMs, toMs]`: day = that local day's [00:00, 24:00); range = [from 00:00, to+1 00:00). `cursorIndex` + `playing` are session state.
- **Base + addresses:** `groupBases(data.items, data.staleAddrs)` → daily-driver base (`DAILY_DRIVER_BASE`, fallback first). `addresses` = its packs. `const points = useTrack(addresses, fromMs, toMs)`.
- **Derived:** `cumMi = cumulativeMiles(points)`; `segKinds = points.map((p,i)=> classifySegment(p, i>0?haversineMi(points[i-1],p):0))`; `hotspots = detectHotspots(points, cumMi)`; `energy = energySeries(points, cumMi)`; `summary = tripSummary(points, cumMi)`.
- **Date toolbar:** ‹ › step `day` by ±1 (or shift the range), native `<input type="date">`, DAY/RANGE `Segmented`.
- **Layout:** `<JourneyMap points segKinds hotspots cursorIndex theme />` (main) + right dock (the base's two packs' SOC `Ring`s + a discharge strip showing `summary.miles`/`activeMiles`/`transitMiles`/`peakW`). Bottom: a **playback bar** — play/pause toggling `playing` (a `setInterval` ~280 ms advancing `cursorIndex` mod `points.length`, cleared on pause/unmount) + a range `<input type="range" min=0 max={points.length-1}>` scrubber bound to `cursorIndex`; readouts for `points[cursorIndex]`: SOC, draw (`power_w` W), distance (`cumMi[cursorIndex]` in `unit`-ish mi), state (`segKinds[cursorIndex]` → ACTIVE/IN TRANSIT/IDLE). Then `<EnergyDistanceChart energy cursorIndex distUnit={mi} />`.
- **Empty** (no points): the map's empty state + a "No GPS trip recorded for this day." message; hide playback.
- On `mobile`, map fixed height, dock + playback + chart stack.
- All colors tokens; reuse the model + components (no duplicated distance/color/classification logic).

```tsx
import type { FleetData } from "../useFleetData";
import type { TempUnit } from "../../temp";
export function JourneyView({ data, theme, unit, mobile }: {
  data: FleetData; theme: "dark" | "light"; unit: TempUnit; mobile: boolean;
}) { /* … */ }
```

- [ ] **Step 2: Verify**

Run: `cd web && npx tsc -b && npm run build` — clean + builds. (Runtime drive in Task 7/8.)

- [ ] **Step 3: Commit**

```bash
git add web/src/v2/views/JourneyView.tsx
git commit -m "feat(web): Journey view — date nav, map, dock, playback, energy chart"
```

---

### Task 7: App wiring (resolved theme + journey branch)

**Files:**
- Modify: `web/src/v2/App.tsx`

**Interfaces:** wires JourneyView with the resolved theme.

- [ ] **Step 1: Thread the resolved theme + mount JourneyView**

In `web/src/v2/App.tsx`: capture `useTheme`'s return — change `useTheme(settings.themeMode);` to
`const resolvedTheme = useTheme(settings.themeMode);`. Add `import { JourneyView } from "./views/JourneyView";`.
Replace the `journey` branch: `view === "journey" ? <JourneyView data={data} theme={resolvedTheme} unit={settings.tempUnitPref} mobile={mobile} /> :`. No other change (single store, other views, nav untouched).

- [ ] **Step 2: Verify build + drive**

Run: `cd web && npx tsc -b && npm test && npm run build` — clean + green + both bundles.
Then drive `/v2/` against a running backend (dev Postgres + local server seeded with an outdoor trip): Journey renders the map + discharge trail, date nav switches days, playback walks the cursor + updates readouts + the energy chart, theme flip re-styles tiles, an indoor-only day shows the empty state. Command/Health/Alerts/Settings/History + v1 unaffected. If no backend, confirm served HTML + clean build; runtime drive is Task 8.

- [ ] **Step 3: Commit**

```bash
git add web/src/v2/App.tsx
git commit -m "feat(web): wire Journey into the shell (resolved theme for tiles) — all six v2 views live"
```

---

## Part E — Verify + docs

### Task 8: Full suite + end-to-end verification + docs

**Files:** docs only.

- [ ] **Step 1: Web suite + types** — `cd web && npm test && npx tsc -b` (all green + no type errors).
- [ ] **Step 2: Server suite** — `docker compose -f server/docker-compose.dev.yml up -d && cd server && .venv/bin/python -m pytest` (all green, incl. `test_track.py` + `test_web_track.py`).
- [ ] **Step 3: Build both bundles** — `cd web && npm run build` (`dist/index.html` + `dist/v2/index.html`; v2 chunk includes leaflet + its CSS; v1 unaffected).
- [ ] **Step 4: Drive v2 end-to-end (verify skill)** — serve dist (or dev server + proxy against a seeded outdoor trip with an active leg, a transit leg, and a hotspot): load `/v2/` → Journey renders the map + discharge-colored trail (+ transit dashes + a hotspot), date nav + playback + energy chart work, theme flip re-tiles, indoor day = empty state; all other views + v1 unaffected. Capture evidence.
- [ ] **Step 5: Docs** — update `docs/superpowers/specs/2026-07-12-webui-v2-roadmap.md` (Phase 4 complete; **all six views live — v2 design fully implemented**) + the `CLAUDE.md` "WebUI v2" subsection (Journey live; `/web/track` added; leaflet dep + CARTO tiles). Commit:

```bash
git add docs/superpowers/specs/2026-07-12-webui-v2-roadmap.md CLAUDE.md
git commit -m "docs: mark WebUI v2 Phase 4 (Journey) complete — all six views live"
```

---

## Self-Review (completed)

**Spec coverage:** `/web/track` → Task 1; web types/decoder/api → Task 2; pure journey model → Task 3; leaflet dep + JourneyMap → Task 4; energy chart + track hook → Task 5; JourneyView (date nav/map/dock/playback/chart) → Task 6; App wiring (resolved theme) → Task 7; verification + docs → Task 8. Every spec §2–§6 item maps to a task.

**Deferred (correctly out of Phase 4):** WS-streamed trail, routing/geocoding, tile self-hosting; the standing Phase-2/3 backlog. §9 open items are implementation-time tunings.

**Type consistency:** `TrackPoint`/`Track` defined once in `track.ts`; consumed by `decodeTrack`, `getTrack`, `mergeBaseTracks`, `useTrack`, `JourneyMap`, `JourneyView`. `SegKind`/`Hotspot`/`EnergyPoint` from `journey.ts` flow into `JourneyMap`/`EnergyDistanceChart`/`JourneyView`. Server `track_series` response fields (`t,lat,lon,power_w,current_a,soc`) match the `TrackPoint` shape. `App` passes `resolvedTheme: "dark"|"light"` (from `useTheme`) to `JourneyView` → `JourneyMap`.
