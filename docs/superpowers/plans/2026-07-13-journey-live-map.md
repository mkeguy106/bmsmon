# Journey Live Map Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the Journey window includes "now", the trail auto-refreshes and a pulsing wheelchair icon tracks the chair live, with follow-until-you-pan camera behavior.

**Architecture:** Pure selectors in `web/src/v2/model/live.ts` (liveness + freshest position); `useTrack` gains an optional refresh interval; `JourneyMap` gains a divIcon wheelchair marker, a `fitKey`-scoped fit-bounds, and follow logic with a programmatic-move guard; `JourneyView` wires it all plus a LIVE badge. WebUI-only — no server or Android changes. Spec: `docs/superpowers/specs/2026-07-13-journey-live-map-design.md`.

**Tech Stack:** React + Leaflet + vitest (web/src/v2).

## Global Constraints

- Trail refresh interval exactly **15 000 ms** while live; marker staleness cutoff exactly **120 000 ms** (older/no fix → marker hidden/null).
- `isLive = toMs > now` (window extends past now).
- Fit-bounds must key on the window (`fitKey`), never on `points` — a live refresh must not disturb the user's pan/zoom. Follow pans only via a programmatic guard; user `dragstart`/`zoomstart` disables follow and reveals a `⌖ FOLLOW` button.
- Playback cursor, energy chart, trip summary behavior unchanged.
- Commit messages: NEVER reference AI/Claude/automated generation.
- Gate: `cd /home/joely/bmsmon/web && npx vitest run && npx tsc --noEmit && npm run build`.

---

### Task 1: Pure live selectors (`live.ts`)

**Files:**
- Create: `web/src/v2/model/live.ts`
- Test: `web/src/v2/model/live.test.ts`

**Interfaces:**
- Consumes: `FleetItem` from `../../types` (fields used: `address`, `ts_ms`, `lat`, `lon`).
- Produces (Task 3/4 rely on):
  - `const LIVE_REFRESH_MS = 15_000`
  - `const LIVE_STALE_MS = 120_000`
  - `interface LivePos { lat: number; lon: number; tsMs: number }`
  - `function isWindowLive(toMs: number, nowMs: number): boolean`
  - `function livePosition(items: FleetItem[], addresses: string[], nowMs: number): LivePos | null`

- [ ] **Step 1: Write the failing test**

```ts
import { describe, expect, it } from "vitest";
import type { FleetItem } from "../../types";
import { LIVE_STALE_MS, isWindowLive, livePosition } from "./live";

const item = (address: string, ts_ms: number, lat: number | null): FleetItem =>
  ({ address, ts_ms, lat, lon: lat == null ? null : -75 } as FleetItem);

describe("isWindowLive", () => {
  it("live only while the window extends past now", () => {
    expect(isWindowLive(1000, 999)).toBe(true);
    expect(isWindowLive(1000, 1000)).toBe(false);
    expect(isWindowLive(1000, 1001)).toBe(false);
  });
});

describe("livePosition", () => {
  const now = 1_000_000;
  it("returns the freshest GPS fix among the base's packs", () => {
    const items = [
      item("A", now - 10_000, 40.1),
      item("B", now - 5_000, 40.2),
      item("X", now - 1_000, 40.9),   // not in the base — ignored
    ];
    const p = livePosition(items, ["A", "B"], now);
    expect(p).toEqual({ lat: 40.2, lon: -75, tsMs: now - 5_000 });
  });
  it("null when the freshest fix is stale", () => {
    const items = [item("A", now - LIVE_STALE_MS - 1, 40.1)];
    expect(livePosition(items, ["A"], now)).toBeNull();
  });
  it("exactly at the cutoff still shows", () => {
    const items = [item("A", now - LIVE_STALE_MS, 40.1)];
    expect(livePosition(items, ["A"], now)).not.toBeNull();
  });
  it("skips items without GPS", () => {
    const items = [item("A", now - 1_000, null), item("B", now - 9_000, 40.3)];
    expect(livePosition(items, ["A", "B"], now)?.lat).toBe(40.3);
  });
  it("null for empty addresses or no matches", () => {
    expect(livePosition([item("A", now, 40)], [], now)).toBeNull();
    expect(livePosition([], ["A"], now)).toBeNull();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/joely/bmsmon/web && npx vitest run src/v2/model/live.test.ts`
Expected: FAIL — cannot resolve `./live`.

- [ ] **Step 3: Write the implementation**

```ts
// Liveness + current-position selectors for the Journey live map. Pure — the view feeds
// them from useFleetData's items (live WS-updated) and its resolved window.
// Design: docs/superpowers/specs/2026-07-13-journey-live-map-design.md
import type { FleetItem } from "../../types";

/** Trail re-poll cadence while live: one server track bucket. */
export const LIVE_REFRESH_MS = 15_000;

/** A fix older than this no longer says where the chair IS — hide the marker. */
export const LIVE_STALE_MS = 120_000;

export interface LivePos { lat: number; lon: number; tsMs: number }

/** Live = the selected window extends past now (day = today, or a range ending today). */
export function isWindowLive(toMs: number, nowMs: number): boolean {
  return toMs > nowMs;
}

/** Freshest GPS-carrying sample among the base's packs, or null when stale/absent. */
export function livePosition(
  items: FleetItem[], addresses: string[], nowMs: number,
): LivePos | null {
  const addrs = new Set(addresses);
  let best: FleetItem | null = null;
  for (const it of items) {
    if (!addrs.has(it.address) || it.lat == null || it.lon == null) continue;
    if (nowMs - it.ts_ms > LIVE_STALE_MS) continue;
    if (!best || it.ts_ms > best.ts_ms) best = it;
  }
  return best ? { lat: best.lat!, lon: best.lon!, tsMs: best.ts_ms } : null;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/joely/bmsmon/web && npx vitest run src/v2/model/live.test.ts`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon
git add web/src/v2/model/live.ts web/src/v2/model/live.test.ts
git commit -m "feat(webui-v2): live-window + freshest-position selectors for the Journey map"
```

---

### Task 2: `useTrack` refresh interval

**Files:**
- Modify: `web/src/v2/useTrack.ts`

**Interfaces:**
- Produces: `useTrack(addresses: string[], fromMs: number, toMs: number, refreshMs?: number): TrackPoint[]` — unchanged behavior when `refreshMs` is undefined.

- [ ] **Step 1: Apply the change**

Replace the whole hook body so the fetch logic is shared by the initial load and the interval (the doc comment gains one sentence; existing sentences stay):

```ts
export function useTrack(
  addresses: string[],
  fromMs: number,
  toMs: number,
  refreshMs?: number,
): TrackPoint[] {
  const [points, setPoints] = useState<TrackPoint[]>([]);
  const key = addresses.join(",") + ":" + fromMs + ":" + toMs + ":" + (refreshMs ?? 0);

  useEffect(() => {
    let alive = true;
    if (addresses.length === 0) {
      setPoints([]);
      return;
    }
    const load = () => {
      Promise.all(
        addresses.map((a) => getTrack(a, fromMs, toMs).catch(() => null)),
      ).then((results) => {
        if (!alive) return;
        const tracks = results.filter((t): t is Track => t != null);
        // Keep last on total failure: only replace when at least one address resolved,
        // so a transient error window doesn't blank the chart.
        if (tracks.length > 0) setPoints(mergeBaseTracks(tracks));
      });
    };
    load();
    // Live windows re-poll on an interval (one server bucket); undefined = fetch once.
    const timer = refreshMs != null ? setInterval(load, refreshMs) : null;
    return () => {
      alive = false;
      if (timer != null) clearInterval(timer);
    };
    // key encodes addresses + window + refresh; the primitives it's built from are the deps.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return points;
}
```

Also extend the JSDoc's last paragraph with: `When [refreshMs] is set, the same window is refetched on that interval (live mode); cleanup clears the timer.`

- [ ] **Step 2: Gate**

Run: `cd /home/joely/bmsmon/web && npx vitest run && npx tsc --noEmit`
Expected: green (hook has no unit tests — established convention; consumers compile).

- [ ] **Step 3: Commit**

```bash
cd /home/joely/bmsmon
git add web/src/v2/useTrack.ts
git commit -m "feat(webui-v2): useTrack optional refresh interval for live windows"
```

---

### Task 3: JourneyMap — wheelchair marker, follow, fitKey

**Files:**
- Modify: `web/src/v2/components/JourneyMap.tsx`
- Modify: `web/src/v2/tokens.css` (pulse keyframes + marker/button classes)

**Interfaces:**
- Consumes: `LivePos` shape (only `{lat, lon}` needed) from Task 1 (import type from `../model/live`).
- Produces: `JourneyMap` props gain `live: LivePos | null` and `fitKey: string` (both required — Task 4 passes them).

- [ ] **Step 1: Add CSS (tokens.css, append at end)**

```css
/* Journey live marker: wheelchair badge + pulse ring (Leaflet divIcon content). */
.chair-marker {
  display: flex; align-items: center; justify-content: center;
  width: 34px; height: 34px; border-radius: 50%;
  background: var(--ok); color: #fff; font-size: 18px; line-height: 1;
  border: 2px solid #fff; box-shadow: 0 1px 6px rgba(0, 0, 0, 0.45);
  position: relative;
}
.chair-marker::after {
  content: ""; position: absolute; inset: -6px; border-radius: 50%;
  border: 2px solid var(--ok); opacity: 0;
  animation: chair-pulse 2s ease-out infinite;
}
@keyframes chair-pulse {
  0% { transform: scale(0.7); opacity: 0.8; }
  70% { transform: scale(1.25); opacity: 0; }
  100% { opacity: 0; }
}
.follow-btn {
  position: absolute; right: 10px; bottom: 10px; z-index: 1000;
  background: var(--panel-2); color: var(--text); border: 1px solid var(--border);
  border-radius: 8px; padding: 6px 10px; font-size: 12px; cursor: pointer;
}
```

- [ ] **Step 2: Extend JourneyMap**

Apply these changes to `JourneyMap.tsx`:

a) Imports/props/refs — signature becomes:

```ts
import type { LivePos } from "../model/live";

export function JourneyMap({ points, segKinds, hotspots, cursorIndex, theme, live, fitKey }: {
  points: TrackPoint[]; segKinds: SegKind[]; hotspots: Hotspot[]; cursorIndex: number;
  theme: "dark" | "light"; live: LivePos | null; fitKey: string;
}) {
```

and add refs/state next to the existing ones:

```ts
  const liveMarkerRef = useRef<L.Marker | null>(null);
  const programmaticMove = useRef(false);
  const [following, setFollowing] = useState(false);
```

b) Map init gate: replace `const hasPoints = points.length > 0;` with

```ts
  const hasPoints = points.length > 0;
  const hasMapContent = hasPoints || live != null;
```

and in the lifecycle effect use `hasMapContent` (dep too), clearing the new ref in its cleanup (`liveMarkerRef.current = null;`). When initializing with no points but a live position, seed the view on the chair instead of the world: `map.setView(live ? [live.lat, live.lon] : [0, 0], live ? 17 : 2);` (the `live` read here is deliberate init-time-only; do not add it to the deps).

c) Follow plumbing — add after the lifecycle effect:

```ts
  // User-initiated pan/zoom breaks follow. Programmatic moves are guarded so panTo/fitBounds
  // never count as the user grabbing the map.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const breakFollow = () => { if (!programmaticMove.current) setFollowing(false); };
    map.on("dragstart", breakFollow);
    map.on("zoomstart", breakFollow);
    return () => { map.off("dragstart", breakFollow); map.off("zoomstart", breakFollow); };
  }, [mapReady]);

  // Going live (or coming back to a live day) re-engages follow; leaving live disengages.
  useEffect(() => { setFollowing(live != null); }, [live != null, fitKey]);
```

d) Live marker + follow pan — add after the playback-cursor effect:

```ts
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (!live) {
      if (liveMarkerRef.current) { map.removeLayer(liveMarkerRef.current); liveMarkerRef.current = null; }
      return;
    }
    const latlng: [number, number] = [live.lat, live.lon];
    if (liveMarkerRef.current) {
      liveMarkerRef.current.setLatLng(latlng);
    } else {
      const icon = L.divIcon({
        className: "",                    // suppress Leaflet's default divIcon box styling
        html: '<div class="chair-marker">♿</div>',
        iconSize: [34, 34], iconAnchor: [17, 17],
      });
      liveMarkerRef.current = L.marker(latlng, { icon, interactive: false, zIndexOffset: 1000 }).addTo(map);
    }
    if (following) {
      programmaticMove.current = true;
      map.panTo(latlng);
      // Leaflet fires moveend after the pan settles; clearing on it re-arms the guard.
      map.once("moveend", () => { programmaticMove.current = false; });
    }
  }, [live, following, mapReady]);
```

e) Fit-bounds: change the fit effect's deps from `[points, mapReady]` to `[fitKey, mapReady]` and guard the programmatic move:

```ts
  useEffect(() => {
    const map = mapRef.current;
    if (!map || points.length === 0) return;
    map.invalidateSize();
    const bounds = L.latLngBounds(points.map((p) => [p.lat, p.lon] as [number, number]));
    if (bounds.isValid()) {
      programmaticMove.current = true;
      map.fitBounds(bounds, { padding: [24, 24], maxZoom: 17 });
      map.once("moveend", () => { programmaticMove.current = false; });
    }
    // Fit once per selected window (fitKey) — NEVER per live refresh of `points`, which
    // would yank the user's pan/zoom every 15 s.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fitKey, mapReady]);
```

f) Empty state + FOLLOW button — replace the `if (!hasPoints)` return and the final return:

```tsx
  if (!hasMapContent) {
    return (
      <div style={{
        height: "100%", minHeight: 360, display: "flex", alignItems: "center",
        justifyContent: "center", background: "var(--panel-3)", border: "1px solid var(--border)",
        borderRadius: 8, color: "var(--text-4)", fontSize: 13,
      }}>
        No GPS trip recorded
      </div>
    );
  }

  return (
    <div style={{ position: "relative", height: "100%" }}>
      <div ref={containerRef} style={{
        height: "100%", minHeight: 360, borderRadius: 8, overflow: "hidden",
        border: "1px solid var(--border)", background: "var(--panel-3)",
      }} />
      {live != null && !following && (
        <button className="follow-btn mono" onClick={() => setFollowing(true)}>⌖ FOLLOW</button>
      )}
    </div>
  );
```

- [ ] **Step 3: Gate**

Run: `cd /home/joely/bmsmon/web && npx vitest run && npx tsc --noEmit && npm run build`
Expected: FAIL at tsc — `JourneyView` doesn't pass the new required props yet. That's expected; commit anyway ONLY if tsc is the sole failure AND the error is exactly the missing props in JourneyView.tsx. Otherwise fix within this task. (Task 4 wires the props; if you prefer a always-green history, give `live`/`fitKey` defaults here and let Task 4 remove them — either is acceptable, note which you chose.)

- [ ] **Step 4: Commit**

```bash
cd /home/joely/bmsmon
git add web/src/v2/components/JourneyMap.tsx web/src/v2/tokens.css
git commit -m "feat(webui-v2): wheelchair live marker, follow-until-pan, window-scoped map fit"
```

---

### Task 4: JourneyView wiring + LIVE badge

**Files:**
- Modify: `web/src/v2/views/JourneyView.tsx`

**Interfaces:**
- Consumes: `isWindowLive`, `livePosition`, `LIVE_REFRESH_MS` (Task 1); `useTrack(..., refreshMs?)` (Task 2); `JourneyMap` `live`/`fitKey` props (Task 3). `data.now` (epoch ms ticking each second) and `data.items` already exist on `FleetData`.

- [ ] **Step 1: Wire it**

a) Imports:

```ts
import { LIVE_REFRESH_MS, isWindowLive, livePosition } from "../model/live";
```

b) After the `addresses` memo, replace the track lines:

```ts
  const isLive = isWindowLive(toMs, data.now);
  const rawPoints = useTrack(addresses, fromMs, toMs, isLive ? LIVE_REFRESH_MS : undefined);
  // Cleaned at render time (spike rejection / stay snapping / smoothing) — raw data stays
  // raw in the DB; the map, miles, energy chart, and playback all consume the cleaned track.
  const points = useMemo(() => cleanTrack(rawPoints), [rawPoints]);
  const live = useMemo(
    () => (isLive ? livePosition(data.items, addresses, data.now) : null),
    [isLive, data.items, addresses, data.now],
  );
  const fitKey = addresses.join(",") + ":" + fromMs + ":" + toMs;
```

c) Pass to the map:

```tsx
          <JourneyMap points={points} segKinds={segKinds} hotspots={hotspots}
            cursorIndex={ci} theme={theme} live={live} fitKey={fitKey} />
```

(If Task 3 gave the props defaults, remove those defaults now so the props are required.)

d) LIVE badge — inside the date-toolbar flex row, after the `<Segmented …/>` element:

```tsx
        {isLive && (
          <span className="mono" style={{
            display: "inline-flex", alignItems: "center", gap: 6,
            color: "var(--live)", fontSize: 11, letterSpacing: 1,
          }}>
            <span style={{
              width: 8, height: 8, borderRadius: "50%", background: "var(--live)",
              animation: "chair-pulse 2s ease-out infinite",
            }} />
            LIVE
          </span>
        )}
```

e) Empty-state copy: the `hasTrip ? … : …` message becomes live-aware:

```tsx
        <div className="mono" style={{ fontSize: 12, color: "var(--text-4)" }}>
          {isLive ? "Waiting for GPS…" : "No GPS trip recorded for this day."}
        </div>
```

- [ ] **Step 2: Full gate**

Run: `cd /home/joely/bmsmon/web && npx vitest run && npx tsc --noEmit && npm run build`
Expected: all green.

- [ ] **Step 3: Commit**

```bash
cd /home/joely/bmsmon
git add web/src/v2/views/JourneyView.tsx
git commit -m "feat(webui-v2): live Journey — auto-refreshing trail, LIVE badge, chair position"
```

---

### Task 5: Deploy + live verification (controller-driven, after final review + merge)

- Push main → image build (`gh run watch`, confirm headSha == HEAD) → NAS pull + `up -d bmsmon-api` → health check.
- Live verification on prod: open v2 Journey with day = today while the chair reports — LIVE badge on, wheelchair marker present and moving with fresh samples, trail extends within ~15 s, pan disengages follow and the ⌖ FOLLOW button restores it, past days unchanged (no badge/marker, fit works).
- Update CLAUDE.md's WebUI v2 mention (one sentence: Journey goes live for today — 15 s trail refresh, WS-driven wheelchair marker, follow-until-pan).
