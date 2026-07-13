# Journey Mobile Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Map-first non-scrolling Journey on mobile — toolbar / map-fills-everything / compact dock (trip line + pair-capacity line + single-direction flow line) — plus on-map overlays, a re-center button on both platforms, and honest trail-metric coloring.

**Architecture:** Pure dock model (`model/dock.ts`) + `socColor` in `model/journey.ts`; `JourneyMap` gains a `metric` prop, an `emptyText` prop, and a persistent crosshair re-center button replacing ⌖ FOLLOW; `JourneyView` branches hard on `mobile` (map-first column with overlays + `JourneyDock`, desktop unchanged); `App.tsx` bounds the shell for journey-mobile and passes `settings.mapMetricPref` through. Spec: `docs/superpowers/specs/2026-07-13-journey-mobile-design.md`.

**Tech Stack:** React + Leaflet + vitest (web/src/v2 only).

## Global Constraints

- Alert-band colors everywhere: `--ok` > 30, `--warn` ≤ 30, `--live` ≤ 15 (CAP bar + socColor).
- Flow: single fill from the left; magnitude `|Σ power_w|` of connected packs vs `PAIR_FLOW_FULL_W = 600`, clamped; discharging (Σ current < −0.1) → `linear-gradient(90deg, var(--warn), var(--live))`, suffix `OUT`; Σ current > 0.1 → green `linear-gradient(90deg, var(--regen, #34d399), var(--ok))`, suffix `REGEN` if any connected pack's `regen === true` else `CHG`; else empty + `0 W · IDLE`.
- Dock chrome exactly: container `padding:12px 14px; gap:9px; borderTop:1px solid var(--border); background:var(--panel-3)`; bars 8 px tall radius 4 on `--track`; eyebrow 9 px letter-spacing .14em `--text-4` 40 px wide; value column 96 px right-aligned 12 px bold + 9 px `--text-3` suffix; tabular-nums.
- Overlay chrome exactly: `background:rgba(9,9,11,.72); border:1px solid var(--border-strong); border-radius:6px` mono 9–10 px letter-spaced; LIVE dot 8 px `--ok` with `box-shadow:0 0 0 3px rgba(34,197,94,.2)`; re-center 38×38 radius 9.
- Mobile renders NO playback bar, NO energy chart, NO desktop side dock, NO toolbar LIVE badge (badge moves onto the map); desktop layout otherwise unchanged.
- Shell bounding applies ONLY when `mobile && view === "journey"`; BottomTabs' 76 px stays reserved.
- Commit messages: NEVER reference AI/Claude/automated generation.
- Gate: `cd /home/joely/bmsmon/web && npx vitest run && npx tsc --noEmit && npm run build`.

---

### Task 1: Pure models — `socColor` + dock

**Files:**
- Modify: `web/src/v2/model/journey.ts` (add `socColor` near `dischargeColor`)
- Create: `web/src/v2/model/dock.ts`
- Test: `web/src/v2/model/dock.test.ts` (covers both)

**Interfaces:**
- Consumes: `BasePack` from `../fleet` (`{ item: FleetItem; letter: string; connected: boolean }`), `DISCHARGE_EPS` from `./journey`.
- Produces (Tasks 2/3 rely on):
  - `journey.ts`: `function socColor(soc: number | null | undefined): string` → `"var(--ok)" | "var(--warn)" | "var(--live)" | "var(--text-4)"` (null/undefined → text-4)
  - `dock.ts`: `const PAIR_FLOW_FULL_W = 600`; `type FlowKind = "out" | "regen" | "chg" | "idle"`; `interface DockFlow { kind: FlowKind; watts: number; frac: number }`; `interface DockCap { pct: number | null; detail: string; band: "ok" | "warn" | "crit" }`; `function dockCapacity(packs: BasePack[]): DockCap`; `function dockFlow(packs: BasePack[]): DockFlow`

- [ ] **Step 1: Write the failing test**

```ts
import { describe, expect, it } from "vitest";
import type { BasePack } from "../fleet";
import type { FleetItem } from "../../types";
import { socColor } from "./journey";
import { PAIR_FLOW_FULL_W, dockCapacity, dockFlow } from "./dock";

const pack = (letter: string, over: Partial<FleetItem>, connected = true): BasePack => ({
  letter, connected,
  item: { address: letter, ts_ms: 0, soc: 68, current_a: 0, power_w: 0, regen: false, ...over } as FleetItem,
});

describe("socColor", () => {
  it("follows the alert bands", () => {
    expect(socColor(68)).toBe("var(--ok)");
    expect(socColor(31)).toBe("var(--ok)");
    expect(socColor(30)).toBe("var(--warn)");
    expect(socColor(16)).toBe("var(--warn)");
    expect(socColor(15)).toBe("var(--live)");
    expect(socColor(null)).toBe("var(--text-4)");
  });
});

describe("dockCapacity", () => {
  it("pair pct is the weaker pack; detail lists both", () => {
    const c = dockCapacity([pack("A", { soc: 69 }), pack("B", { soc: 68 })]);
    expect(c).toEqual({ pct: 68, detail: "A69·B68", band: "ok" });
  });
  it("bands: warn at <=30, crit at <=15", () => {
    expect(dockCapacity([pack("A", { soc: 24 })]).band).toBe("warn");
    expect(dockCapacity([pack("A", { soc: 12 })]).band).toBe("crit");
  });
  it("disconnected packs don't drive pct but still show in detail with a dash", () => {
    const c = dockCapacity([pack("A", { soc: 69 }), pack("B", { soc: null }, false)]);
    expect(c.pct).toBe(69);
    expect(c.detail).toBe("A69·B—");
  });
  it("single pack: no detail suffix", () => {
    expect(dockCapacity([pack("A", { soc: 42 })]).detail).toBe("");
  });
  it("nothing reachable: pct null", () => {
    expect(dockCapacity([pack("A", { soc: 69 }, false)]).pct).toBeNull();
  });
});

describe("dockFlow", () => {
  it("discharging pair sums to OUT with fraction of 600 W", () => {
    const f = dockFlow([
      pack("A", { current_a: -3.1, power_w: 77 }),
      pack("B", { current_a: -3.0, power_w: 77 }),
    ]);
    expect(f.kind).toBe("out");
    expect(f.watts).toBe(154);
    expect(f.frac).toBeCloseTo(154 / PAIR_FLOW_FULL_W, 5);
  });
  it("charging reads CHG; regen flag flips it to REGEN", () => {
    expect(dockFlow([pack("A", { current_a: 4, power_w: 105 })]).kind).toBe("chg");
    expect(dockFlow([pack("A", { current_a: 4, power_w: 105, regen: true })]).kind).toBe("regen");
  });
  it("idle inside the ±0.1 A deadband", () => {
    expect(dockFlow([pack("A", { current_a: 0.05, power_w: 1 })])).toEqual({ kind: "idle", watts: 0, frac: 0 });
  });
  it("fraction clamps at 1 on hard pulls", () => {
    expect(dockFlow([pack("A", { current_a: -60, power_w: 882 })]).frac).toBe(1);
  });
  it("disconnected packs are excluded from the sums", () => {
    const f = dockFlow([pack("A", { current_a: -3, power_w: 80 }), pack("B", { current_a: -3, power_w: 80 }, false)]);
    expect(f.watts).toBe(80);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/joely/bmsmon/web && npx vitest run src/v2/model/dock.test.ts`
Expected: FAIL — cannot resolve `./dock` / `socColor` not exported.

- [ ] **Step 3: Implement**

Add to `web/src/v2/model/journey.ts` (right after `dischargeColor`):

```ts
/** Trail/capacity color by SOC, on the alert bands: ok > 30, warn ≤ 30, live ≤ 15. */
export function socColor(soc: number | null | undefined): string {
  if (soc == null) return "var(--text-4)";
  if (soc <= 15) return "var(--live)";
  if (soc <= 30) return "var(--warn)";
  return "var(--ok)";
}
```

Create `web/src/v2/model/dock.ts`:

```ts
// Pure model for the mobile Journey dock's two lines: pair capacity + single-direction flow.
// Design: docs/superpowers/specs/2026-07-13-journey-mobile-design.md
import type { BasePack } from "../fleet";
import { DISCHARGE_EPS } from "./journey";

/** Flow full scale: 2 × the 300 W per-pack ring calibration (POWER_RING_FULL_W, android). */
export const PAIR_FLOW_FULL_W = 600;

export type FlowKind = "out" | "regen" | "chg" | "idle";
export interface DockFlow { kind: FlowKind; watts: number; frac: number }
export interface DockCap { pct: number | null; detail: string; band: "ok" | "warn" | "crit" }

/** Pair capacity: the weaker reachable pack ends the trip; per-pack detail keeps A/B visible. */
export function dockCapacity(packs: BasePack[]): DockCap {
  const reachable = packs.filter((p) => p.connected && p.item.soc != null);
  const pct = reachable.length ? Math.round(Math.min(...reachable.map((p) => p.item.soc!))) : null;
  const detail = packs.length > 1
    ? packs.map((p) => `${p.letter}${p.item.soc != null ? Math.round(p.item.soc) : "—"}`).join("·")
    : "";
  const band = pct == null || pct > 30 ? "ok" : pct > 15 ? "warn" : "crit";
  return { pct, detail, band };
}

/** One flow line: direction from the summed pair current, magnitude vs the 600 W full scale. */
export function dockFlow(packs: BasePack[]): DockFlow {
  const live = packs.filter((p) => p.connected);
  const current = live.reduce((s, p) => s + (p.item.current_a ?? 0), 0);
  const watts = Math.round(Math.abs(live.reduce((s, p) => s + (p.item.power_w ?? 0), 0)));
  const frac = Math.min(1, watts / PAIR_FLOW_FULL_W);
  if (current < -DISCHARGE_EPS) return { kind: "out", watts, frac };
  if (current > DISCHARGE_EPS) {
    return { kind: live.some((p) => p.item.regen === true) ? "regen" : "chg", watts, frac };
  }
  return { kind: "idle", watts: 0, frac: 0 };
}
```

- [ ] **Step 4: Run tests**

Run: `cd /home/joely/bmsmon/web && npx vitest run src/v2/model/dock.test.ts`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon
git add web/src/v2/model/journey.ts web/src/v2/model/dock.ts web/src/v2/model/dock.test.ts
git commit -m "feat(webui-v2): dock capacity/flow model + SOC trail color bands"
```

---

### Task 2: JourneyMap — metric coloring, emptyText, re-center replaces FOLLOW

**Files:**
- Modify: `web/src/v2/components/JourneyMap.tsx`
- Modify: `web/src/v2/tokens.css` (retire `.follow-btn`; add `.recenter-btn`)

**Interfaces:**
- Consumes: `socColor` (Task 1).
- Produces: `JourneyMap` props become `{ points, segKinds, hotspots, cursorIndex, theme, live, fitKey, metric: "power" | "soc", emptyText?: string }`. The ⌖ FOLLOW button is gone; a persistent crosshair re-center button renders whenever the map does.

- [ ] **Step 1: CSS (tokens.css)** — replace the `.follow-btn` rule with:

```css
.recenter-btn {
  position: absolute; bottom: 12px; right: 12px; z-index: 1000;
  width: 38px; height: 38px; border-radius: 9px;
  background: rgba(9, 9, 11, .72); border: 1px solid var(--border-strong);
  display: flex; align-items: center; justify-content: center;
  color: var(--text-2); cursor: pointer;
}
```

- [ ] **Step 2: Component changes**

a) Signature + imports:

```ts
import { dischargeColor, socColor, type SegKind, type Hotspot } from "../model/journey";

export function JourneyMap({ points, segKinds, hotspots, cursorIndex, theme, live, fitKey, metric, emptyText }: {
  points: TrackPoint[]; segKinds: SegKind[]; hotspots: Hotspot[]; cursorIndex: number;
  theme: "dark" | "light"; live: LivePos | null; fitKey: string;
  metric: "power" | "soc"; emptyText?: string;
}) {
```

b) Trail coloring — in the trail effect, the active branch becomes:

```ts
      if (kind === "active") {
        const color = metric === "soc"
          ? resolveColor(socColor(cur.soc))
          : trailColor(cur.power_w);
        L.polyline(line, { color, weight: 4, opacity: 0.95 }).addTo(group);
      } else {
```

and `metric` joins that effect's dep array.

c) Re-center — replace the FOLLOW button block in the JSX with a persistent button, and add the handler next to the other callbacks:

```ts
  // Re-center: on the chair when live (re-engaging follow), else refit to the trail.
  const recenter = () => {
    const map = mapRef.current;
    if (!map) return;
    if (live) {
      setFollowing(true);
      programmaticMove.current = true;
      map.panTo([live.lat, live.lon]);
      map.once("moveend", () => { programmaticMove.current = false; });
    } else if (points.length > 0) {
      const bounds = L.latLngBounds(points.map((p) => [p.lat, p.lon] as [number, number]));
      if (bounds.isValid()) {
        programmaticMove.current = true;
        map.fitBounds(bounds, { padding: [24, 24], maxZoom: 17 });
        map.once("moveend", () => { programmaticMove.current = false; });
      }
    }
  };
```

```tsx
      <button className="recenter-btn" aria-label="Re-center map" onClick={recenter}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <circle cx="12" cy="12" r="7" /><circle cx="12" cy="12" r="1.6" fill="currentColor" />
          <path d="M12 2v3M12 19v3M2 12h3M19 12h3" />
        </svg>
      </button>
```

d) Empty state text: `{emptyText ?? "No GPS trip recorded"}` in the placeholder div.

- [ ] **Step 3: Gate** — `cd /home/joely/bmsmon/web && npx vitest run && npx tsc --noEmit` — tsc will fail ONLY at the JourneyView call site missing `metric`; add the prop there in this task as `metric="power"` placeholder ONLY if you want a green interim commit, or fix forward in Task 3 within the same PR — note which you chose. (Task 3 wires the real pref.)

- [ ] **Step 4: Commit**

```bash
cd /home/joely/bmsmon
git add web/src/v2/components/JourneyMap.tsx web/src/v2/tokens.css web/src/v2/views/JourneyView.tsx
git commit -m "feat(webui-v2): metric-aware trail color + persistent re-center (replaces FOLLOW)"
```

---

### Task 3: JourneyView mobile layout + dock + App shell + pref pass-through

**Files:**
- Create: `web/src/v2/components/JourneyDock.tsx`
- Modify: `web/src/v2/views/JourneyView.tsx`
- Modify: `web/src/v2/App.tsx`

**Interfaces:**
- Consumes: `dockCapacity`, `dockFlow`, `PAIR_FLOW_FULL_W` (Task 1 — frac already computed in `dockFlow`), `TripSummary` from `../model/journey`, `BasePack` from `../fleet`, JourneyMap's `metric`/`emptyText` props (Task 2).
- Produces: `JourneyView` props gain `mapMetric: "power" | "soc"`; `JourneyDock` component `{ summary: TripSummary; packs: BasePack[] }`.

- [ ] **Step 1: JourneyDock component** (`web/src/v2/components/JourneyDock.tsx`, complete file):

```tsx
import type { CSSProperties } from "react";
import type { BasePack } from "../fleet";
import type { TripSummary } from "../model/journey";
import { dockCapacity, dockFlow, type FlowKind } from "../model/dock";

const eyebrow: CSSProperties = {
  fontSize: 9, letterSpacing: ".14em", color: "var(--text-4)", width: 40, flexShrink: 0,
};
const rowStyle: CSSProperties = { display: "flex", alignItems: "center", gap: 9 };
const barStyle: CSSProperties = {
  position: "relative", flex: 1, height: 8, borderRadius: 4,
  background: "var(--track)", overflow: "hidden",
};
const valStyle: CSSProperties = {
  width: 96, flexShrink: 0, textAlign: "right", fontSize: 12, fontWeight: 700, color: "var(--text)",
};

const CAP_FILL: Record<"ok" | "warn" | "crit", string> = {
  ok: "var(--ok)", warn: "var(--warn)", crit: "var(--live)",
};
const FLOW_FILL: Record<Exclude<FlowKind, "idle">, string> = {
  out: "linear-gradient(90deg, var(--warn), var(--live))",
  regen: "linear-gradient(90deg, #34d399, var(--ok))",
  chg: "linear-gradient(90deg, #34d399, var(--ok))",
};
const FLOW_LABEL: Record<FlowKind, string> = { out: "OUT", regen: "REGEN", chg: "CHG", idle: "IDLE" };

function Fill({ frac, background }: { frac: number; background: string }) {
  return <div style={{ position: "absolute", inset: "0 auto 0 0", width: `${frac * 100}%`,
    borderRadius: 4, background, transition: "width .6s ease" }} />;
}

/** Mobile Journey dock: one trip line + pair-capacity line + single-direction flow line. */
export function JourneyDock({ summary, packs }: { summary: TripSummary; packs: BasePack[] }) {
  const cap = dockCapacity(packs);
  const flow = dockFlow(packs);
  const sep = <span style={{ color: "var(--text-4)" }}>·</span>;
  const b = (t: string) => <b style={{ color: "var(--text)", fontSize: 12 }}>{t}</b>;
  return (
    <div className="mono" style={{
      padding: "12px 14px", display: "flex", flexDirection: "column", gap: 9, flexShrink: 0,
      borderTop: "1px solid var(--border)", background: "var(--panel-3)",
      fontVariantNumeric: "tabular-nums",
    }}>
      <div style={{ fontSize: 11, color: "var(--text-2)", display: "flex", gap: 6,
        flexWrap: "wrap", alignItems: "baseline" }}>
        {b(`${summary.miles.toFixed(1)} mi`)}{sep}
        <span>ACT {b(summary.activeMiles.toFixed(1))}</span>{sep}
        <span>TRN {b(summary.transitMiles.toFixed(1))}</span>{sep}
        <span>PEAK {b(`${Math.round(summary.peakW)} W`)}</span>
      </div>
      <div style={rowStyle}>
        <span style={eyebrow}>CAP</span>
        <div style={barStyle}>
          {cap.pct != null && <Fill frac={cap.pct / 100} background={CAP_FILL[cap.band]} />}
        </div>
        <span style={valStyle}>
          {cap.pct != null ? `${cap.pct}%` : "—"}
          {cap.detail && <small style={{ color: "var(--text-3)", fontWeight: 400, fontSize: 9 }}> {cap.detail}</small>}
        </span>
      </div>
      <div style={rowStyle}>
        <span style={eyebrow}>FLOW</span>
        <div style={barStyle}>
          {flow.kind !== "idle" && <Fill frac={flow.frac} background={FLOW_FILL[flow.kind]} />}
        </div>
        <span style={valStyle}>
          {flow.watts} W
          <small style={{ color: "var(--text-3)", fontWeight: 400, fontSize: 9 }}> {FLOW_LABEL[flow.kind]}</small>
        </span>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: App.tsx** — pass the pref and bound the shell:

```ts
  const journeyMobile = mobile && view === "journey";
```

Journey line: `<JourneyView data={data} theme={resolvedTheme} unit={settings.tempUnitPref} mobile={mobile} mapMetric={settings.mapMetricPref} />`

Shell root div: `style={{ display: "flex", ...(journeyMobile ? { height: "100dvh", overflow: "hidden" } : { minHeight: "100vh" }) }}`

`main`: `style={journeyMobile ? { padding: "0 0 76px", flex: 1, display: "flex", flexDirection: "column", minHeight: 0 } : { padding: mobile ? "16px 14px 76px" : 18, flex: 1 }}`

- [ ] **Step 3: JourneyView restructure** — add prop `mapMetric: "power" | "soc"`; imports `JourneyDock`; overlay chrome helper:

```ts
const overlayChrome: CSSProperties = {
  position: "absolute", background: "rgba(9,9,11,.72)", border: "1px solid var(--border-strong)",
  borderRadius: 6, padding: "5px 9px", fontSize: 9, letterSpacing: ".12em", zIndex: 1000,
};
```

Root + toolbar: root div style becomes `mobile ? { flex: 1, display: "flex", flexDirection: "column", minHeight: 0, overflow: "hidden" } : { display: "flex", flexDirection: "column", gap: 16 }`; the toolbar div gains `...(mobile && { padding: "12px 14px 8px", flexShrink: 0 })`; the toolbar LIVE badge renders only `{!mobile && isLive && (…existing badge…)}`.

Body: replace the current map+dock grid AND the playback/empty block with:

```tsx
      {mobile ? (
        <>
          <div style={{ flex: 1, minHeight: 0, position: "relative" }}>
            <JourneyMap points={points} segKinds={segKinds} hotspots={hotspots}
              cursorIndex={Math.max(0, points.length - 1)} theme={theme} live={live} fitKey={fitKey}
              metric={mapMetric} emptyText={isLive ? "Waiting for GPS…" : "No GPS trip recorded"} />
            <span className="mono" style={{ ...overlayChrome, top: 12, left: 12, color: "var(--text-2)" }}>
              TRAIL · {mapMetric.toUpperCase()}
            </span>
            {isLive && (
              <span className="mono" style={{ ...overlayChrome, top: 12, right: 12, color: "var(--text)",
                display: "flex", alignItems: "center", gap: 6 }}>
                <span style={{ width: 8, height: 8, borderRadius: "50%", background: "var(--ok)",
                  boxShadow: "0 0 0 3px rgba(34,197,94,.2)" }} />
                LIVE · GPS
              </span>
            )}
            <span className="mono" style={{ ...overlayChrome, bottom: 12, left: 12, color: "var(--text-3)",
              display: "flex", gap: 9 }}>
              {([["transit", "var(--text-4)"], ["light", "var(--ok)"], ["mod", "var(--warn)"], ["heavy", "var(--live)"]] as const)
                .map(([label, color]) => (
                  <span key={label} style={{ display: "inline-flex", alignItems: "center", gap: 4 }}>
                    <span style={{ width: 12, height: 3, borderRadius: 2, background: color }} />{label}
                  </span>
                ))}
            </span>
          </div>
          <JourneyDock summary={summary} packs={base?.packs ?? []} />
        </>
      ) : (
        <>
          {/* …the existing desktop grid (map + side dock) and the existing
               playback-bar/energy-chart/empty-text block, verbatim, with the JourneyMap call
               gaining metric={mapMetric} … */}
        </>
      )}
```

(The desktop branch is the CURRENT body moved inside the fragment unchanged, except `<JourneyMap …>` gains `metric={mapMetric}` — and remove the Task-2 placeholder `metric="power"` if one was added.)

Note: on mobile, the `cursorIndex`/`playing` state and their effects remain but are inert (playback UI not rendered); do not delete them — desktop uses them.

- [ ] **Step 4: Full gate**

Run: `cd /home/joely/bmsmon/web && npx vitest run && npx tsc --noEmit && npm run build`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
cd /home/joely/bmsmon
git add web/src/v2/components/JourneyDock.tsx web/src/v2/views/JourneyView.tsx web/src/v2/App.tsx
git commit -m "feat(webui-v2): map-first mobile Journey — line dock, on-map overlays, bounded shell"
```

---

### Task 4: Deploy + docs + on-phone verification (controller-driven, after final review + merge)

- CLAUDE.md WebUI-v2 paragraph: append one sentence — mobile Journey is map-first (no scroll; playback desktop-only) with a line dock (pair CAP + single-direction FLOW vs 600 W) and on-map overlays; re-center replaces FOLLOW on both platforms; `mapMetricPref` now actually colors the trail.
- Push main → image build (`gh run watch`, headSha == HEAD) → NAS pull + `up -d bmsmon-api` → health.
- On-phone verification: v2 Journey on the Pixel — no page scroll, map dominates, dock shows trip line + CAP/FLOW states, LIVE·GPS + chip + legend overlays present, re-center works live and on past days, Settings map-metric toggle flips the chip AND the trail colors; desktop view unchanged except re-center button.
