# WebUI v2 — Phase 1 (Foundation + Command) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a live, high-fidelity v2 dashboard at `bmsmon.covert.life/v2/` — the shared app shell (nav, top bar, System/Light/Dark theming, responsive mobile) plus a fully data-bound **Command** view — while v1 (`/`) stays untouched; and land a cell-voltage telemetry pipeline so Command shows real C1–C4.

**Architecture:** v2 lives inside the existing `web/` project under `web/src/v2/`, reusing the v1 data layer (`api.ts`, `ws.ts`, `store.ts`, `decode.ts`, `range.ts`). A second Vite rollup input (`web/v2/index.html`) emits `dist/v2/index.html` sharing `dist/assets`; the server's existing `StaticFiles(html=True)` mount serves `GET /v2/` with no backend change. The cell pipeline extends android's live upload → server `samples` (4 discrete `cellN_v` columns) → `fleet_snapshot` (assembled into a `cells` array) → web decoder → Command.

**Tech Stack:** React 18 + Vite + TypeScript + vitest (web); FastAPI + asyncpg + Postgres 16 + pytest (server); Kotlin + kotlinx.serialization (android).

## Global Constraints

- **No new npm dependencies.** Icons, rings, charts, gauges are inline SVG. (No React-testing-library — tests are pure-logic vitest, matching the existing suite.)
- **v1 is never modified.** No edits to `web/index.html`, `web/src/App.tsx`, or v1 components. Shared data-layer files may only be extended additively (new optional fields/keys).
- **Theme tokens are exact.** Use the verbatim hex/rgba values from the Phase 1 spec §3.
- **Formulas mirror production.** Range/runtime reuse `web/src/range.ts` unchanged. No re-derivation.
- **Numbers are monospace.** JetBrains Mono (already loaded) for all metrics, codes, axis labels, chips, eyebrows.
- **Schema changes are additive** (`ADD COLUMN IF NOT EXISTS`) — they auto-apply on container start; no migration step.
- **Persistence keys:** `bmsmon-v2-view`, `bmsmon-v2-nav`, `bmsmon-v2-settings`, `bmsmon-v2-trips`. Reconcile theme/unit with existing `bmsmon-theme` / `bmsmon-temp-unit`.
- **Local dev/test:** web `cd web && npm test`; server `cd server && .venv/bin/python -m pytest`; Postgres via `docker compose -f server/docker-compose.dev.yml up -d`.

---

## Part A — Cell-voltage telemetry pipeline

### Task 1: Server — persist & expose per-cell voltages

**Files:**
- Modify: `server/app/db/schema.sql` (samples columns)
- Modify: `server/app/models.py:15-38` (`SampleIn`)
- Modify: `server/app/db/queries.py:7-46` (`_COLS`, `_INSERT`, `_INSERT_FIELDS`, `sample_row`) and `:190-205` (`fleet_snapshot` SELECT)
- Test: `server/tests/test_cells.py` (create)

**Interfaces:**
- Consumes: nothing new.
- Produces: ingest accepts `SampleIn.cells: list[float] | None`; `fleet_snapshot` rows include `cells: list[float] | None` (4-element array of C1–C4, or `None` when no cell data). WS frames already carry `cells` via `model_dump()`.

- [ ] **Step 1: Write the failing test**

Create `server/tests/test_cells.py`. Follow the existing async pytest + pool fixture pattern used in the other `server/tests/*.py` (import the same `pool`/`conn` fixtures and helpers those tests use; mirror one that already inserts a sample and reads `fleet_snapshot`).

```python
import pytest
from app.db import queries as q

pytestmark = pytest.mark.asyncio


async def test_cells_persist_and_surface_in_snapshot(conn, a_device_and_battery):
    device_id, address = a_device_and_battery  # existing fixture: enrolled device + registered battery
    row = q.sample_row(device_id, address, {
        "ts_ms": 1_800_000_000_000, "soc": 88.0, "current_a": -4.0, "power_w": -51.0,
        "voltage_v": 13.2, "temp_c": 24.0, "soh": 99, "full_charge_ah": 100.0,
        "remaining_ah": 88.0, "cycles": 12, "cell_min_v": 3.31, "cell_max_v": 3.34,
        "cells": [3.32, 3.31, 3.34, 3.33],
    })
    assert await q.insert_samples(conn, [row]) == 1
    snap = {r["address"]: r for r in await q.fleet_snapshot(conn)}
    assert snap[address]["cells"] == [3.32, 3.31, 3.34, 3.33]


async def test_absent_cells_gives_null(conn, a_device_and_battery):
    device_id, address = a_device_and_battery
    row = q.sample_row(device_id, address, {
        "ts_ms": 1_800_000_001_000, "soc": 90.0, "cell_min_v": 3.30, "cell_max_v": 3.33,
    })
    assert await q.insert_samples(conn, [row]) == 1
    snap = {r["address"]: r for r in await q.fleet_snapshot(conn)}
    assert snap[address]["cells"] is None
```

(If the existing tests don't expose an `a_device_and_battery` fixture, reuse whatever the nearest existing snapshot test uses to seed a battery + device, and adapt these two assertions onto it.)

- [ ] **Step 2: Run to verify it fails**

Run: `cd server && .venv/bin/python -m pytest tests/test_cells.py -v`
Expected: FAIL — `sample_row`/insert doesn't handle `cells`; `fleet_snapshot` has no `cells` key (KeyError / None mismatch).

- [ ] **Step 3: Add the schema columns**

In `server/app/db/schema.sql`, in the `CREATE TABLE IF NOT EXISTS samples (...)` block add after the `cell_min_v real, cell_max_v real,` line:

```sql
  cell1_v real, cell2_v real, cell3_v real, cell4_v real,
```

And add an additive guard near the other `ALTER TABLE samples ADD COLUMN IF NOT EXISTS` statements (so existing deployments gain them):

```sql
ALTER TABLE samples ADD COLUMN IF NOT EXISTS cell1_v real;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS cell2_v real;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS cell3_v real;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS cell4_v real;
```

- [ ] **Step 4: Accept `cells` on ingest**

In `server/app/models.py`, add to `SampleIn` after `cell_max_v`:

```python
    cells: list[float] | None = None
```

- [ ] **Step 5: Write the 4 cell columns in the insert**

In `server/app/db/queries.py`, extend `sample_row` (after the `_COLS` loop, before `return row`):

```python
    cells = s.get("cells")
    for i in range(4):
        row[f"cell{i + 1}_v"] = cells[i] if cells and i < len(cells) else None
```

In `_INSERT`, add the four columns to the INSERT column list and four `$24::real[]…$27::real[]` params to the `unnest(...)`:

```sql
    (device_id,address,ts_ms,ts,state,soc,current_a,power_w,voltage_v,temp_c,
     mosfet_temp_c,soh,full_charge_ah,remaining_ah,cycles,cell_min_v,cell_max_v,regen,link_event,
     lat,lon,gps_accuracy_m,eta_full_min,cell1_v,cell2_v,cell3_v,cell4_v)
  SELECT * FROM unnest(
    $1::uuid[], $2::text[], $3::bigint[], $4::timestamptz[], $5::text[],
    $6::real[], $7::real[], $8::real[], $9::real[], $10::real[],
    $11::int[], $12::int[], $13::real[], $14::real[], $15::int[],
    $16::real[], $17::real[], $18::boolean[], $19::text[],
    $20::float8[], $21::float8[], $22::real[], $23::real[],
    $24::real[], $25::real[], $26::real[], $27::real[])
```

Append to `_INSERT_FIELDS`:

```python
                  "eta_full_min", "cell1_v", "cell2_v", "cell3_v", "cell4_v"]
```

- [ ] **Step 6: Return `cells` from the snapshot**

In `fleet_snapshot`'s SELECT (queries.py ~line 195), add after `s.eta_full_min, s.received_at,`:

```sql
              CASE WHEN s.cell1_v IS NULL THEN NULL
                   ELSE ARRAY[s.cell1_v, s.cell2_v, s.cell3_v, s.cell4_v] END AS cells,
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd server && .venv/bin/python -m pytest tests/test_cells.py -v`
Expected: PASS (both). Then full suite: `cd server && .venv/bin/python -m pytest` — Expected: all green (no regressions).

- [ ] **Step 8: Commit**

```bash
git add server/app/db/schema.sql server/app/models.py server/app/db/queries.py server/tests/test_cells.py
git commit -m "feat(server): persist and expose per-cell voltages in ingest + fleet snapshot"
```

---

### Task 2: Web data layer — decode `cells` (+ min/max fallback fields)

**Files:**
- Modify: `web/src/types.ts:1-9` (`Sample`)
- Modify: `web/src/decode.ts:13-17` (`SAMPLE_KEYS`) + add a `cells` normalizer
- Test: `web/src/decode.test.ts` (add cases)

**Interfaces:**
- Consumes: `fleet_snapshot`/WS `cells: number[] | null`.
- Produces: `Sample.cells?: number[] | null`, `Sample.cell_min_v?`, `Sample.cell_max_v?`, `Sample.mosfet_temp_c?` available to v2 components. `cells` is kept only when it is an array of finite numbers; otherwise dropped (→ min/max fallback).

- [ ] **Step 1: Write the failing test**

Add to `web/src/decode.test.ts` (match the file's existing `describe`/`it` + `decodeSample`/`decodeFleetItem` usage):

```ts
it("keeps a valid cells array", () => {
  const s = decodeSample({ address: "AA", ts_ms: 1, cells: [3.31, 3.32, 3.34, 3.33] });
  expect(s?.cells).toEqual([3.31, 3.32, 3.34, 3.33]);
});

it("drops a cells array containing non-finite / null entries", () => {
  const s = decodeSample({ address: "AA", ts_ms: 1, cells: [3.31, null, 3.34, 3.33] });
  expect(s?.cells).toBeUndefined();
});

it("passes cell_min_v / cell_max_v through", () => {
  const s = decodeSample({ address: "AA", ts_ms: 1, cell_min_v: 3.30, cell_max_v: 3.35 });
  expect(s?.cell_min_v).toBe(3.30);
  expect(s?.cell_max_v).toBe(3.35);
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd web && npm test -- decode`
Expected: FAIL — `cells`/`cell_min_v` stripped by the whitelist (undefined) or a malformed `cells` retained.

- [ ] **Step 3: Extend the types**

In `web/src/types.ts`, add to `Sample` (after `eta_full_min`):

```ts
  cell_min_v?: number | null; cell_max_v?: number | null; mosfet_temp_c?: number | null;
  cells?: number[] | null;
```

- [ ] **Step 4: Whitelist the keys and normalize `cells`**

In `web/src/decode.ts`, add to `SAMPLE_KEYS` (after `"eta_full_min",`):

```ts
  "cell_min_v", "cell_max_v", "mosfet_temp_c", "cells",
```

Then, at the end of `decodeSample` and `decodeFleetItem`, run the picked object through a normalizer that drops a malformed `cells`. Add this helper and apply it:

```ts
const normCells = <T extends { cells?: unknown }>(s: T): T => {
  const c = s.cells;
  if (!(Array.isArray(c) && c.length > 0 && c.every((x) => typeof x === "number" && Number.isFinite(x)))) {
    if ("cells" in (s as object)) delete (s as { cells?: unknown }).cells;
  }
  return s;
};
```

Change `decodeSample` to `return normCells(pick<Sample>(x, SAMPLE_KEYS));` and `decodeFleetItem` to `return normCells(pick<FleetItem>(x, FLEET_KEYS));`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd web && npm test -- decode`
Expected: PASS (all decode tests).

- [ ] **Step 6: Commit**

```bash
git add web/src/types.ts web/src/decode.ts web/src/decode.test.ts
git commit -m "feat(web): decode per-cell voltages + min/max fallback fields"
```

---

### Task 3: Android — upload live per-cell voltages

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/cloud/CloudJson.kt:10-63` (`SampleJson`, `sampleJson`)
- Modify: `android/app/src/main/java/dev/joely/bmsmon/cloud/TelemetryReporter.kt:121-127` (live `report` call site)
- Test: `android/app/src/test/java/dev/joely/bmsmon/cloud/CloudJsonTest.kt` (create or extend — match the module's existing JVM unit-test package layout under `app/src/test`)

**Interfaces:**
- Consumes: `Telemetry.cells: List<Float>` (already computed in `BmsProtocol`).
- Produces: live telemetry upload JSON includes `"cells":[…]` when non-empty (omitted otherwise, matching `explicitNulls=false`). Import/link paths unchanged (no cells).

- [ ] **Step 1: Write the failing test**

Create/extend `CloudJsonTest.kt` (JUnit, matching existing android JVM tests):

```kotlin
package dev.joely.bmsmon.cloud

import org.junit.Assert.assertTrue
import org.junit.Test

class CloudJsonTest {
    @Test fun sampleJson_includes_cells_when_present() {
        val js = CloudJson.sampleJson(
            1L, "AA", null, null, null, "Discharging", 88f, -4f, -51f, 13.2f, 24f,
            null, 99, 100f, 88f, 12, 3.31f, 3.34f, false, null,
            cells = listOf(3.32f, 3.31f, 3.34f, 3.33f),
        )
        assertTrue(js.contains("\"cells\":[3.32,3.31,3.34,3.33]"))
    }

    @Test fun sampleJson_omits_cells_when_null() {
        val js = CloudJson.sampleJson(
            1L, "AA", null, null, null, "Idle", 88f, 0f, 0f, 13.2f, 24f,
            null, 99, 100f, 88f, 12, 3.31f, 3.34f, false, null,
        )
        assertTrue(!js.contains("\"cells\""))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.cloud.CloudJsonTest"`
Expected: FAIL — `sampleJson` has no `cells` param (compile error), and `SampleJson` has no `cells` field.

- [ ] **Step 3: Add the field + param**

In `CloudJson.kt`, add to `SampleJson` after `cell_max_v`:

```kotlin
    val cells: List<Float>? = null,
```

Add a `cells` parameter to `sampleJson(...)` (after `etaFullMin`) and pass it through (drop non-finite; empty → null):

```kotlin
        etaFullMin: Float? = null,
        cells: List<Float>? = null,
    ): String = json.encodeToString(
        SampleJson.serializer(),
        SampleJson(tsMs, address, advertisedName, alias, groupId, state,
            soc.finiteOrNull(), currentA.finiteOrNull(), powerW.finiteOrNull(),
            voltageV.finiteOrNull(), tempC.finiteOrNull(), mosfetTempC, soh,
            fullChargeAh.finiteOrNull(), remainingAh.finiteOrNull(), cycles,
            cellMinV.finiteOrNull(), cellMaxV.finiteOrNull(), regen, linkEvent,
            lat.finiteOrNull(), lon.finiteOrNull(), gpsAccuracyM.finiteOrNull(),
            etaFullMin.finiteOrNull(),
            cells?.filter { it.isFinite() }?.takeIf { it.isNotEmpty() }),
    )
```

- [ ] **Step 4: Pass live cells at the report call site**

In `TelemetryReporter.kt` live `report(...)` (the `CloudJson.sampleJson(` around line 121-127), add the trailing arg:

```kotlin
            lat, lon, gpsAccuracyM, etaFullMin,
            cells = t.cells.takeIf { it.isNotEmpty() },
```

(Leave `runImport` and `reportLink` unchanged — they upload without per-cell data.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.cloud.CloudJsonTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/cloud/CloudJson.kt \
        android/app/src/main/java/dev/joely/bmsmon/cloud/TelemetryReporter.kt \
        android/app/src/test/java/dev/joely/bmsmon/cloud/CloudJsonTest.kt
git commit -m "feat(android): upload live per-cell voltages with each telemetry sample"
```

---

## Part B — v2 build scaffold

### Task 4: v2 entry + multi-page build + hello shell

**Files:**
- Create: `web/v2/index.html`
- Create: `web/src/v2/main.tsx`
- Create: `web/src/v2/App.tsx` (temporary hello, replaced in Task 9)
- Modify: `web/vite.config.ts` (add second rollup input)

**Interfaces:**
- Produces: `GET /v2/` serves the v2 SPA from `dist/v2/index.html`; v1 still at `/`.

- [ ] **Step 1: Add the second build input**

In `web/vite.config.ts`, add `build.rollupOptions.input` (keep the existing `plugins`/`server` config):

```ts
export default defineConfig({
  plugins: [react()],
  build: { rollupOptions: { input: { main: "index.html", v2: "v2/index.html" } } },
  server: {
    proxy: {
      "/web": "http://localhost:8000",
      "/api": "http://localhost:8000",
      "/ws": { target: "ws://localhost:8000", ws: true },
    },
  },
});
```

- [ ] **Step 2: Create the v2 HTML entry**

Create `web/v2/index.html` (mirrors v1's head: pre-paint theme, fonts, dark-reader lock; script points at the v2 entry):

```html
<!doctype html>
<html lang="en">
  <head><meta charset="utf-8" /><title>bmsmon · v2</title>
    <meta name="color-scheme" content="light dark" />
    <meta name="darkreader-lock" />
    <script>
      try {
        var m = JSON.parse(localStorage.getItem("bmsmon-v2-settings") || "{}").themeMode
             || (localStorage.getItem("bmsmon-theme") === "light" ? "light" : "system");
        var dark = m === "dark" || (m !== "light" && matchMedia("(prefers-color-scheme: dark)").matches);
        document.documentElement.dataset.theme = dark ? "dark" : "light";
      } catch (e) { document.documentElement.dataset.theme = "dark"; }
    </script>
    <link rel="preconnect" href="https://fonts.googleapis.com" />
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600;700&display=swap" rel="stylesheet" />
  </head>
  <body><div id="root"></div><script type="module" src="/src/v2/main.tsx"></script></body>
</html>
```

- [ ] **Step 3: Create the v2 entry + hello App**

Create `web/src/v2/main.tsx`:

```tsx
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./tokens.css";

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
```

Create a temporary `web/src/v2/App.tsx`:

```tsx
export default function App() {
  return <div style={{ padding: 24, fontFamily: "Inter, sans-serif" }}>bmsmon v2 — shell coming online</div>;
}
```

Create a minimal `web/src/v2/tokens.css` placeholder (fleshed out in Task 5) so the import resolves:

```css
:root[data-theme="dark"], :root[data-theme="light"] { }
```

- [ ] **Step 4: Verify both bundles build and serve**

Run: `cd web && npm run build`
Expected: build succeeds; `web/dist/index.html` and `web/dist/v2/index.html` both exist, sharing `web/dist/assets/`.
Verify: `ls web/dist web/dist/v2` shows both `index.html`s.
Then (with a server running against dist, or via `python -c` static check): confirm `dist/v2/index.html` references `/assets/…` absolute URLs.

- [ ] **Step 5: Commit**

```bash
git add web/vite.config.ts web/v2/index.html web/src/v2/main.tsx web/src/v2/App.tsx web/src/v2/tokens.css
git commit -m "feat(web): v2 build entry served at /v2/ (multi-page vite build)"
```

---

## Part C — App shell

### Task 5: Design tokens + theme hook

**Files:**
- Modify: `web/src/v2/tokens.css` (full token set + base element styles)
- Create: `web/src/v2/useTheme.ts`
- Test: `web/src/v2/useTheme.test.ts`

**Interfaces:**
- Produces: `resolveTheme(mode: ThemeMode, prefersDark: boolean): "dark" | "light"`; `useTheme(mode)` applies `data-theme` and follows OS changes. `type ThemeMode = "system" | "light" | "dark"`.

- [ ] **Step 1: Write the failing test**

Create `web/src/v2/useTheme.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { resolveTheme } from "./useTheme";

describe("resolveTheme", () => {
  it("honors explicit light/dark", () => {
    expect(resolveTheme("light", true)).toBe("light");
    expect(resolveTheme("dark", false)).toBe("dark");
  });
  it("follows OS preference in system mode", () => {
    expect(resolveTheme("system", true)).toBe("dark");
    expect(resolveTheme("system", false)).toBe("light");
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd web && npm test -- useTheme`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement the hook**

Create `web/src/v2/useTheme.ts`:

```ts
import { useEffect, useState } from "react";

export type ThemeMode = "system" | "light" | "dark";
export type Resolved = "dark" | "light";

export function resolveTheme(mode: ThemeMode, prefersDark: boolean): Resolved {
  if (mode === "light" || mode === "dark") return mode;
  return prefersDark ? "dark" : "light";
}

/** Applies the resolved theme to <html data-theme> and live-follows OS changes in system mode. */
export function useTheme(mode: ThemeMode): Resolved {
  const [prefersDark, setPrefersDark] = useState(
    () => matchMedia("(prefers-color-scheme: dark)").matches,
  );
  useEffect(() => {
    const mq = matchMedia("(prefers-color-scheme: dark)");
    const on = () => setPrefersDark(mq.matches);
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, []);
  const resolved = resolveTheme(mode, prefersDark);
  useEffect(() => { document.documentElement.dataset.theme = resolved; }, [resolved]);
  return resolved;
}
```

- [ ] **Step 4: Fill in the tokens**

Replace `web/src/v2/tokens.css` with the full token set (exact values from spec §3), status colors, base element styles, and a `.mono` helper:

```css
:root[data-theme="dark"] {
  --app-bg:#09090b; --nav-bg:#08080a; --panel:#18181b; --panel-2:#202024; --panel-3:#131316;
  --track:#27272a; --border:rgba(255,255,255,.07); --border-strong:rgba(255,255,255,.11);
  --hover:rgba(255,255,255,.04); --nav-active:rgba(255,255,255,.06); --row-alt:rgba(255,255,255,.012);
  --text:#f4f4f5; --text-2:#d4d4d8; --text-3:#a1a1aa; --text-4:#71717a; --text-5:#52525b;
  color-scheme: dark;
}
:root[data-theme="light"] {
  --app-bg:#fafafa; --nav-bg:#f1f1f3; --panel:#ffffff; --panel-2:#f4f4f5; --panel-3:#f4f4f5;
  --track:#e4e4e7; --border:rgba(0,0,0,.09); --border-strong:rgba(0,0,0,.14);
  --hover:rgba(0,0,0,.045); --nav-active:rgba(0,0,0,.06); --row-alt:rgba(0,0,0,.02);
  --text:#18181b; --text-2:#3f3f46; --text-3:#52525b; --text-4:#71717a; --text-5:#a1a1aa;
  color-scheme: light;
}
:root { --ok:#22c55e; --warn:#eab308; --live:#ef4444; }
* { box-sizing: border-box; }
body { margin:0; background:var(--app-bg); color:var(--text);
  font-family:Inter,system-ui,sans-serif; font-size:13px; }
.mono { font-family:"JetBrains Mono",ui-monospace,monospace; }
.eyebrow { font-family:"JetBrains Mono",monospace; font-size:9.5px; letter-spacing:.13em;
  text-transform:uppercase; color:var(--text-4); }
.card { background:var(--panel); border:1px solid var(--border); border-radius:8px; padding:16px; }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd web && npm test -- useTheme`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add web/src/v2/tokens.css web/src/v2/useTheme.ts web/src/v2/useTheme.test.ts
git commit -m "feat(web): v2 design tokens + system/light/dark theme hook"
```

---

### Task 6: Settings store + device-mode hook

**Files:**
- Create: `web/src/v2/settings.ts` (types, codec, defaults, device-mode resolver)
- Create: `web/src/v2/useV2Settings.ts` (localStorage-backed)
- Test: `web/src/v2/settings.test.ts`

**Interfaces:**
- Produces:
  `type V2Settings = { distUnit:"mi"|"km"; tempUnitPref:"F"|"C"; mapMetricPref:"power"|"soc"; themeMode: ThemeMode; deviceMode:"mobile"|"desktop"|null }`;
  `DEFAULT_V2_SETTINGS`; `settingsCodec: Codec<V2Settings>`;
  `resolveMobile(deviceMode, winW): boolean` (true when `deviceMode==="mobile"`, false when `"desktop"`, else `winW < 820`);
  `useV2Settings()` → `[settings, patch]`.

- [ ] **Step 1: Write the failing test**

Create `web/src/v2/settings.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { DEFAULT_V2_SETTINGS, resolveMobile, settingsCodec } from "./settings";

describe("resolveMobile", () => {
  it("auto-resolves on width when no override", () => {
    expect(resolveMobile(null, 700)).toBe(true);
    expect(resolveMobile(null, 1000)).toBe(false);
  });
  it("honors an explicit override", () => {
    expect(resolveMobile("desktop", 500)).toBe(false);
    expect(resolveMobile("mobile", 2000)).toBe(true);
  });
});

describe("settingsCodec", () => {
  it("round-trips and fills defaults for a partial blob", () => {
    const raw = JSON.stringify({ distUnit: "km" });
    const decoded = settingsCodec.decode(raw)!;
    expect(decoded.distUnit).toBe("km");
    expect(decoded.themeMode).toBe(DEFAULT_V2_SETTINGS.themeMode);
  });
  it("returns null for garbage", () => {
    expect(settingsCodec.decode("not json")).toBeNull();
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd web && npm test -- settings`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement settings + hook**

Create `web/src/v2/settings.ts`:

```ts
import type { Codec } from "../useLocalStorage";
import type { ThemeMode } from "./useTheme";

export interface V2Settings {
  distUnit: "mi" | "km";
  tempUnitPref: "F" | "C";
  mapMetricPref: "power" | "soc";
  themeMode: ThemeMode;
  deviceMode: "mobile" | "desktop" | null;
}

export const DEFAULT_V2_SETTINGS: V2Settings = {
  distUnit: "mi", tempUnitPref: "F", mapMetricPref: "power", themeMode: "system", deviceMode: null,
};

const MOBILE_MAX = 820;
export function resolveMobile(deviceMode: V2Settings["deviceMode"], winW: number): boolean {
  if (deviceMode === "mobile") return true;
  if (deviceMode === "desktop") return false;
  return winW < MOBILE_MAX;
}

export const settingsCodec: Codec<V2Settings> = {
  decode: (raw) => {
    try {
      const o = JSON.parse(raw);
      if (typeof o !== "object" || o === null) return null;
      return { ...DEFAULT_V2_SETTINGS, ...(o as Partial<V2Settings>) };
    } catch { return null; }
  },
  encode: (v) => JSON.stringify(v),
};
```

Create `web/src/v2/useV2Settings.ts`:

```ts
import { useCallback } from "react";
import { readStored, useLocalStorage } from "../useLocalStorage";
import { DEFAULT_V2_SETTINGS, settingsCodec, type V2Settings } from "./settings";

/** localStorage-backed v2 settings. Seeds themeMode from the legacy bmsmon-theme on first run. */
export function useV2Settings() {
  const [settings, setSettings] = useLocalStorage<V2Settings>(
    "bmsmon-v2-settings",
    () => {
      if (readStored("bmsmon-v2-settings", settingsCodec.decode) != null) return DEFAULT_V2_SETTINGS;
      const legacy = (() => { try { return localStorage.getItem("bmsmon-theme"); } catch { return null; } })();
      return { ...DEFAULT_V2_SETTINGS, themeMode: legacy === "light" || legacy === "dark" ? legacy : "system" };
    },
    settingsCodec,
  );
  const patch = useCallback(
    (p: Partial<V2Settings>) => setSettings((prev) => ({ ...prev, ...p })),
    [setSettings],
  );
  return [settings, patch] as const;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd web && npm test -- settings`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web/src/v2/settings.ts web/src/v2/useV2Settings.ts web/src/v2/settings.test.ts
git commit -m "feat(web): v2 settings store + device-mode resolver"
```

---

### Task 7: Icons + left Nav

**Files:**
- Create: `web/src/v2/components/icons.tsx`
- Create: `web/src/v2/components/Nav.tsx`
- Create: `web/src/v2/nav.ts` (nav model + view type)
- Test: `web/src/v2/nav.test.ts`

**Interfaces:**
- Produces: `type V2View = "command"|"health"|"journey"|"history"|"alerts"|"settings"`;
  `NAV_GROUPS: { label:string; items: { view:V2View; label:string; icon:IconName; disabled?:boolean; badge?:boolean }[] }[]`;
  `<Nav view collapsed unackedCount onSelect onToggleCollapse />`;
  `<Icon name size />`.

- [ ] **Step 1: Write the failing test**

Create `web/src/v2/nav.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { NAV_GROUPS } from "./nav";

describe("NAV_GROUPS", () => {
  it("has MONITOR + MANAGE groups covering all six views plus a disabled Devices", () => {
    const items = NAV_GROUPS.flatMap((g) => g.items);
    expect(NAV_GROUPS.map((g) => g.label)).toEqual(["MONITOR", "MANAGE"]);
    expect(items.filter((i) => !i.disabled).map((i) => i.view)).toEqual(
      ["command", "health", "journey", "history", "alerts", "settings"],
    );
    expect(items.find((i) => i.disabled)?.label).toBe("Devices");
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd web && npm test -- nav`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement the nav model**

Create `web/src/v2/nav.ts`:

```ts
import type { IconName } from "./components/icons";

export type V2View = "command" | "health" | "journey" | "history" | "alerts" | "settings";

export interface NavItem { view: V2View; label: string; icon: IconName; disabled?: boolean; badge?: boolean }
export interface NavGroup { label: string; items: NavItem[] }

export const NAV_GROUPS: NavGroup[] = [
  { label: "MONITOR", items: [
    { view: "command", label: "Command", icon: "grid" },
    { view: "health", label: "Fleet Health", icon: "activity" },
    { view: "journey", label: "Journey", icon: "map-pin" },
    { view: "history", label: "History", icon: "bar-chart" },
  ] },
  { label: "MANAGE", items: [
    { view: "alerts", label: "Alerts", icon: "bell", badge: true },
    { view: "command" as V2View, label: "Devices", icon: "cpu", disabled: true },
    { view: "settings", label: "Settings", icon: "gear" },
  ] },
];
```

- [ ] **Step 4: Implement icons + Nav component**

Create `web/src/v2/components/icons.tsx` with inline 24×24 SVGs (`stroke=currentColor` width 1.7). Define `type IconName = "grid"|"activity"|"map-pin"|"bar-chart"|"bell"|"cpu"|"gear"|"chevron"|"sun"|"moon"|"monitor"` and an `Icon` component switching on `name`. Use standard lucide-style paths, e.g.:

```tsx
export type IconName = "grid"|"activity"|"map-pin"|"bar-chart"|"bell"|"cpu"|"gear"|"chevron"|"sun"|"moon"|"monitor";
const P: Record<IconName, JSX.Element> = {
  grid: <><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></>,
  activity: <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>,
  "map-pin": <><path d="M21 10c0 7-9 12-9 12s-9-5-9-12a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></>,
  "bar-chart": <><line x1="12" y1="20" x2="12" y2="10"/><line x1="18" y1="20" x2="18" y2="4"/><line x1="6" y1="20" x2="6" y2="16"/></>,
  bell: <><path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.7 21a2 2 0 0 1-3.4 0"/></>,
  cpu: <><rect x="4" y="4" width="16" height="16" rx="2"/><rect x="9" y="9" width="6" height="6"/><path d="M9 1v3M15 1v3M9 20v3M15 20v3M20 9h3M20 14h3M1 9h3M1 14h3"/></>,
  gear: <><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></>,
  chevron: <polyline points="9 18 15 12 9 6"/>,
  sun: <><circle cx="12" cy="12" r="5"/><path d="M12 1v2M12 21v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M1 12h2M21 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4"/></>,
  moon: <path d="M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8z"/>,
  monitor: <><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></>,
};
export function Icon({ name, size = 20 }: { name: IconName; size?: number }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round">{P[name]}</svg>;
}
```

Create `web/src/v2/components/Nav.tsx`: renders `NAV_GROUPS`; width 216 (expanded) / 64 (collapsed); each item is a button with `Icon` + label (label hidden when collapsed); active item gets a left accent bar + `--nav-active` bg + brighter text; hover `--hover`; disabled items show a "SOON" tag and are non-interactive; the Alerts item shows `unackedCount` as a badge when > 0; a footer chevron toggles collapse (rotates). Style with the tokens. Signature:

```tsx
export function Nav({ view, collapsed, unackedCount, onSelect, onToggleCollapse }: {
  view: V2View; collapsed: boolean; unackedCount: number;
  onSelect: (v: V2View) => void; onToggleCollapse: () => void;
}) { /* … token-styled markup per spec §4.1 … */ }
```

- [ ] **Step 5: Run test + typecheck**

Run: `cd web && npm test -- nav && npx tsc -b`
Expected: PASS + no type errors.

- [ ] **Step 6: Commit**

```bash
git add web/src/v2/nav.ts web/src/v2/nav.test.ts web/src/v2/components/icons.tsx web/src/v2/components/Nav.tsx
git commit -m "feat(web): v2 nav model, icon set, and left-nav component"
```

---

### Task 8: Top bar + mobile bottom tab bar

**Files:**
- Create: `web/src/v2/components/TopBar.tsx`
- Create: `web/src/v2/components/BottomTabs.tsx`

**Interfaces:**
- Consumes: `V2View`, `NAV_GROUPS`, `Icon`, `ThemeMode`.
- Produces:
  `<TopBar view live gps synced themeMode mobile onCycleTheme onToggleDevice onSelectView />`;
  `<BottomTabs view unackedCount onSelect />`.

- [ ] **Step 1: Implement TopBar**

Create `web/src/v2/components/TopBar.tsx` (53px tall, token-styled). Left: brand `b`+`v2` + current view title. Right (desktop): view-switcher circles for Command/Health/Journey, LIVE / GPS / synced pills (green dot when active, `--text-4` otherwise), a theme-cycle button (System→Light→Dark; `Icon` `monitor`/`sun`/`moon` + label), and a Desktop/Mobile toggle (`Icon monitor`). On `mobile`, hide the pills and the circles; keep brand + title + device/theme toggles.

```tsx
export function TopBar({ view, live, gps, synced, themeMode, mobile, onCycleTheme, onToggleDevice, onSelectView }: {
  view: V2View; live: boolean; gps: boolean; synced: boolean;
  themeMode: ThemeMode; mobile: boolean;
  onCycleTheme: () => void; onToggleDevice: () => void; onSelectView: (v: V2View) => void;
}) { /* … */ }
```

- [ ] **Step 2: Implement BottomTabs**

Create `web/src/v2/components/BottomTabs.tsx`: a fixed bottom bar (all six views, icon + short label), active tab tinted `--text`, the Alerts tab showing `unackedCount` badge. Only rendered on mobile (App decides).

```tsx
export function BottomTabs({ view, unackedCount, onSelect }: {
  view: V2View; unackedCount: number; onSelect: (v: V2View) => void;
}) { /* … fixed; bottom:0; height ~56; token-styled … */ }
```

- [ ] **Step 3: Typecheck**

Run: `cd web && npx tsc -b`
Expected: no type errors. (Visual verification happens in Task 9/17.)

- [ ] **Step 4: Commit**

```bash
git add web/src/v2/components/TopBar.tsx web/src/v2/components/BottomTabs.tsx
git commit -m "feat(web): v2 top bar + mobile bottom tab bar"
```

---

### Task 9: App shell assembly + placeholder views

**Files:**
- Replace: `web/src/v2/App.tsx` (the Task 4 hello)
- Create: `web/src/v2/components/Placeholder.tsx`
- Create: `web/src/v2/useWinWidth.ts`

**Interfaces:**
- Consumes: `useTheme`, `useV2Settings`, `useLocalStorage`, `Nav`, `TopBar`, `BottomTabs`, `resolveMobile`.
- Produces: the running shell — nav + top bar + content area, view persisted to `bmsmon-v2-view`, nav-collapse to `bmsmon-v2-nav`; Command renders a placeholder for now (replaced in Task 16); the other five render `<Placeholder>`.

- [ ] **Step 1: Implement the width hook**

Create `web/src/v2/useWinWidth.ts`:

```ts
import { useEffect, useState } from "react";
export function useWinWidth(): number {
  const [w, setW] = useState(() => window.innerWidth);
  useEffect(() => {
    const on = () => setW(window.innerWidth);
    window.addEventListener("resize", on);
    return () => window.removeEventListener("resize", on);
  }, []);
  return w;
}
```

- [ ] **Step 2: Implement Placeholder**

Create `web/src/v2/components/Placeholder.tsx`:

```tsx
export function Placeholder({ title }: { title: string }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center",
      gap: 8, padding: "120px 0", color: "var(--text-4)" }}>
      <span className="eyebrow">{title}</span>
      <span style={{ fontSize: 13 }}>Coming in a later phase.</span>
    </div>
  );
}
```

- [ ] **Step 3: Assemble the shell**

Replace `web/src/v2/App.tsx`:

```tsx
import { useCallback } from "react";
import { useLocalStorage, type Codec } from "../useLocalStorage";
import { useV2Settings } from "./useV2Settings";
import { useTheme, type ThemeMode } from "./useTheme";
import { useWinWidth } from "./useWinWidth";
import { resolveMobile } from "./settings";
import { Nav } from "./components/Nav";
import { TopBar } from "./components/TopBar";
import { BottomTabs } from "./components/BottomTabs";
import { Placeholder } from "./components/Placeholder";
import type { V2View } from "./nav";

const viewCodec: Codec<V2View> = {
  decode: (r) => (["command","health","journey","history","alerts","settings"].includes(r) ? (r as V2View) : null),
  encode: (v) => v,
};
const boolCodec: Codec<boolean> = { decode: (r) => (r === "1" ? true : r === "0" ? false : null), encode: (v) => (v ? "1" : "0") };
const THEME_CYCLE: ThemeMode[] = ["system", "light", "dark"];

export default function App() {
  const [settings, patch] = useV2Settings();
  useTheme(settings.themeMode);
  const [view, setView] = useLocalStorage<V2View>("bmsmon-v2-view", () => "command", viewCodec);
  const [collapsed, setCollapsed] = useLocalStorage<boolean>("bmsmon-v2-nav", () => false, boolCodec);
  const mobile = resolveMobile(settings.deviceMode, useWinWidth());
  const cycleTheme = useCallback(() => patch({
    themeMode: THEME_CYCLE[(THEME_CYCLE.indexOf(settings.themeMode) + 1) % 3],
  }), [patch, settings.themeMode]);
  const toggleDevice = useCallback(() => patch({
    deviceMode: settings.deviceMode === "mobile" ? "desktop" : "mobile",
  }), [patch, settings.deviceMode]);

  const unacked = 0; // wired in Phase 2

  const content =
    view === "command" ? <Placeholder title="COMMAND" /> : // replaced in Task 16
    view === "health" ? <Placeholder title="FLEET HEALTH" /> :
    view === "journey" ? <Placeholder title="JOURNEY" /> :
    view === "history" ? <Placeholder title="HISTORY" /> :
    view === "alerts" ? <Placeholder title="ALERTS" /> :
    <Placeholder title="SETTINGS" />;

  return (
    <div style={{ display: "flex", minHeight: "100vh" }}>
      {!mobile && (
        <Nav view={view} collapsed={collapsed} unackedCount={unacked}
          onSelect={setView} onToggleCollapse={() => setCollapsed((c) => !c)} />
      )}
      <div style={{ flex: 1, display: "flex", flexDirection: "column", minWidth: 0 }}>
        <TopBar view={view} live={false} gps={false} synced={false}
          themeMode={settings.themeMode} mobile={mobile}
          onCycleTheme={cycleTheme} onToggleDevice={toggleDevice} onSelectView={setView} />
        <main style={{ padding: mobile ? "16px 14px 76px" : 18, flex: 1 }}>{content}</main>
      </div>
      {mobile && <BottomTabs view={view} unackedCount={unacked} onSelect={setView} />}
    </div>
  );
}
```

- [ ] **Step 4: Verify build + manual nav**

Run: `cd web && npm run build`
Expected: builds clean. Then `npm run dev`, open `http://localhost:5173/v2/`: nav switches all six views (Command shows COMMAND placeholder), collapse persists across reload, theme cycles System→Light→Dark and follows the tokens, resizing below 820px swaps the sidebar for the bottom tab bar, and the Desktop/Mobile toggle overrides it.

- [ ] **Step 5: Commit**

```bash
git add web/src/v2/App.tsx web/src/v2/components/Placeholder.tsx web/src/v2/useWinWidth.ts
git commit -m "feat(web): v2 app shell (nav + top bar + theming + responsive) with placeholder views"
```

---

## Part D — Command view

### Task 10: Fleet → bases grouping helper

**Files:**
- Create: `web/src/v2/fleet.ts`
- Test: `web/src/v2/fleet.test.ts`

**Interfaces:**
- Consumes: `FleetItem[]`, `staleAddrs: Set<string>`.
- Produces:
  `interface BasePack { item: FleetItem; letter: "A"|"B"|string; connected: boolean }`
  `interface Base { id: string; label: string; packs: BasePack[]; status: "in-use"|"charging"|"backup"|"spares"|"offline" }`
  `groupBases(items: FleetItem[], staleAddrs: Set<string>): Base[]` — groups by `group_id`, orders packs by alias (index 0→A, 1→B), computes per-base status; bases ordered daily-driver (2012) first then by id.
  `isCharging(i: FleetItem): boolean` (`(i.current_a ?? 0) > 0.1`), `isDischarging`, `deltaMv(i): number | null`.

- [ ] **Step 1: Write the failing test**

Create `web/src/v2/fleet.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { groupBases, deltaMv } from "./fleet";
import type { FleetItem } from "../types";

const mk = (o: Partial<FleetItem>): FleetItem => ({ address: "x", ts_ms: 1, ...o });

describe("groupBases", () => {
  const items = [
    mk({ address: "a1", alias: "2012-A", group_id: "2012", soc: 88, current_a: -4 }),
    mk({ address: "a2", alias: "2012-B", group_id: "2012", soc: 90, current_a: -3 }),
    mk({ address: "b1", alias: "2016-A", group_id: "2016", soc: 100, current_a: 5 }),
    mk({ address: "b2", alias: "2016-B", group_id: "2016", soc: 99, current_a: 4 }),
  ];
  it("groups by base, labels A/B by alias order, daily-driver first", () => {
    const bases = groupBases(items, new Set());
    expect(bases[0].id).toBe("2012");
    expect(bases[0].packs.map((p) => p.letter)).toEqual(["A", "B"]);
    expect(bases[0].status).toBe("in-use");
    expect(bases.find((b) => b.id === "2016")!.status).toBe("charging");
  });
  it("marks a base offline when all packs are stale", () => {
    const bases = groupBases(items, new Set(["b1", "b2"]));
    expect(bases.find((b) => b.id === "2016")!.status).toBe("offline");
  });
});

describe("deltaMv", () => {
  it("returns spread in mV from cells", () => {
    expect(deltaMv(mk({ cells: [3.30, 3.34, 3.31, 3.32] }))).toBeCloseTo(40, 5);
  });
  it("falls back to cell_min_v/cell_max_v", () => {
    expect(deltaMv(mk({ cell_min_v: 3.30, cell_max_v: 3.35 }))).toBeCloseTo(50, 5);
  });
  it("returns null with no cell data", () => {
    expect(deltaMv(mk({}))).toBeNull();
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd web && npm test -- fleet`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement the grouping**

Create `web/src/v2/fleet.ts`:

```ts
import type { FleetItem } from "../types";

export const DAILY_DRIVER_BASE = "2012";

export const isCharging = (i: FleetItem): boolean => (i.current_a ?? 0) > 0.1;
export const isDischarging = (i: FleetItem): boolean => (i.current_a ?? 0) < -0.1;

/** Cell spread in mV: from the real 4 cells when present, else cell_max_v − cell_min_v, else null. */
export function deltaMv(i: FleetItem): number | null {
  if (i.cells && i.cells.length > 0) return (Math.max(...i.cells) - Math.min(...i.cells)) * 1000;
  if (i.cell_min_v != null && i.cell_max_v != null) return (i.cell_max_v - i.cell_min_v) * 1000;
  return null;
}

export interface BasePack { item: FleetItem; letter: string; connected: boolean }
export type BaseStatus = "in-use" | "charging" | "backup" | "spares" | "offline";
export interface Base { id: string; label: string; packs: BasePack[]; status: BaseStatus }

const LETTERS = ["A", "B", "C", "D"];

function baseStatus(packs: BasePack[]): BaseStatus {
  const live = packs.filter((p) => p.connected);
  if (live.length === 0) return "offline";
  if (live.some((p) => isDischarging(p.item))) return "in-use";
  if (live.some((p) => isCharging(p.item))) return "charging";
  const anyFull = live.some((p) => (p.item.soc ?? 0) >= 99);
  return anyFull ? "backup" : "spares";
}

export function groupBases(items: FleetItem[], staleAddrs: Set<string>): Base[] {
  const byBase = new Map<string, FleetItem[]>();
  for (const i of items) {
    const gid = i.group_id ?? i.address;
    (byBase.get(gid) ?? byBase.set(gid, []).get(gid)!).push(i);
  }
  const bases: Base[] = [];
  for (const [id, group] of byBase) {
    const sorted = group.slice().sort((a, b) => (a.alias ?? "").localeCompare(b.alias ?? ""));
    const packs: BasePack[] = sorted.map((item, n) => ({
      item, letter: LETTERS[n] ?? String(n + 1), connected: !staleAddrs.has(item.address),
    }));
    bases.push({ id, label: `Base ${id}`, packs, status: baseStatus(packs) });
  }
  return bases.sort((a, b) =>
    a.id === DAILY_DRIVER_BASE ? -1 : b.id === DAILY_DRIVER_BASE ? 1 : a.id.localeCompare(b.id));
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd web && npm test -- fleet`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web/src/v2/fleet.ts web/src/v2/fleet.test.ts
git commit -m "feat(web): v2 fleet→bases grouping + cell-spread helper"
```

---

### Task 11: Saved trips + range card logic

**Files:**
- Create: `web/src/v2/trips.ts`
- Test: `web/src/v2/trips.test.ts`

**Interfaces:**
- Consumes: `PackRange` from `../range`.
- Produces:
  `interface Trip { id: string; name: string; miles: number }`
  `type TripVerdict = "go" | "tight" | "no-go"`
  `classifyTrip(miles: number, r: PackRange): TripVerdict` — go if `miles ≤ r.milesLo`, tight if `≤ r.milesHi`, else no-go.
  `tripsCodec: Codec<Trip[]>` (persist `bmsmon-v2-trips`).

- [ ] **Step 1: Write the failing test**

Create `web/src/v2/trips.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { classifyTrip, tripsCodec } from "./trips";
import type { PackRange } from "../range";

const r: PackRange = { milesLo: 20, milesHi: 32, activeHLo: 8, activeHHi: 12, wallHLo: 60, wallHHi: 96 };

describe("classifyTrip", () => {
  it("go when within the conservative low bound", () => expect(classifyTrip(15, r)).toBe("go"));
  it("tight between lo and hi", () => expect(classifyTrip(28, r)).toBe("tight"));
  it("no-go beyond the high bound", () => expect(classifyTrip(40, r)).toBe("no-go"));
});

describe("tripsCodec", () => {
  it("round-trips a trip list", () => {
    const trips = [{ id: "1", name: "Clinic", miles: 6.2 }];
    expect(tripsCodec.decode(tripsCodec.encode(trips))).toEqual(trips);
  });
  it("returns null for garbage", () => expect(tripsCodec.decode("{")).toBeNull());
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd web && npm test -- trips`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement**

Create `web/src/v2/trips.ts`:

```ts
import type { Codec } from "../useLocalStorage";
import type { PackRange } from "../range";

export interface Trip { id: string; name: string; miles: number }
export type TripVerdict = "go" | "tight" | "no-go";

export function classifyTrip(miles: number, r: PackRange): TripVerdict {
  if (miles <= r.milesLo) return "go";
  if (miles <= r.milesHi) return "tight";
  return "no-go";
}

export const tripsCodec: Codec<Trip[]> = {
  decode: (raw) => {
    try {
      const a = JSON.parse(raw);
      if (!Array.isArray(a)) return null;
      return a.filter((t): t is Trip =>
        t && typeof t.id === "string" && typeof t.name === "string" && Number.isFinite(t.miles));
    } catch { return null; }
  },
  encode: (v) => JSON.stringify(v),
};
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd web && npm test -- trips`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web/src/v2/trips.ts web/src/v2/trips.test.ts
git commit -m "feat(web): v2 saved-trips model + go/tight/no-go classification"
```

---

### Task 12: Command shared UI atoms (ring, stat tile, bars, cell tiles)

**Files:**
- Create: `web/src/v2/components/Ring.tsx`
- Create: `web/src/v2/components/Atoms.tsx` (StatTile, Bar, Chip, CellTiles)

**Interfaces:**
- Consumes: `FleetItem`, `deltaMv`.
- Produces: token-styled presentational atoms:
  `<Ring soc power current connected size />` (outer SOC arc + inner power arc; colors from tokens/status);
  `<StatTile label value sub />`, `<Bar frac color />`, `<Chip tone>…</Chip>`,
  `<CellTiles item />` (4 tiles C1–C4 from `item.cells`, else high/low/mean from min/max; Δ readout amber if > 40).

- [ ] **Step 1: Implement Ring**

Create `web/src/v2/components/Ring.tsx` — a v2 token-styled ring modeled on v1 `web/src/components/Ring.tsx` but using the v2 status vars (`--ok`/`--warn`/`--live`) and `--track`. Outer arc = SOC (color: `<15 --live`, `<30 --warn`, else `--ok`; muted `--text-4` when disconnected). Inner arc = |power|/300 (green when charging `current>0.1`, `--warn`/power tone when discharging). Center label = `${round(soc)}%` or `—`.

```tsx
export function Ring({ soc, power, current, connected, size = 132 }: {
  soc?: number | null; power?: number | null; current?: number | null; connected: boolean; size?: number;
}) { /* … SVG per above, stroke 9 (outer)/7 (inner) … */ }
```

- [ ] **Step 2: Implement Atoms (incl. CellTiles)**

Create `web/src/v2/components/Atoms.tsx`. `CellTiles` renders four tiles when `item.cells` present (label C1–C4, 3-decimal V, a balance `Bar` scaled within the pack's min–max), else three tiles HIGH/LOW/MEAN from `cell_min_v`/`cell_max_v`; below them a Δ readout `${round(deltaMv)} mV` colored `--warn` when `> 40`, else `--text-3`; renders nothing when `deltaMv(item)` is null.

```tsx
import { deltaMv } from "../fleet";
import type { FleetItem } from "../../types";

export function Bar({ frac, color }: { frac: number; color: string }) {
  return <div style={{ height: 4, background: "var(--track)", borderRadius: 3 }}>
    <div style={{ width: `${Math.max(0, Math.min(1, frac)) * 100}%`, height: "100%", background: color, borderRadius: 3 }} /></div>;
}
export function StatTile({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return <div className="card" style={{ padding: 12 }}>
    <div className="eyebrow">{label}</div>
    <div className="mono" style={{ fontSize: 16, marginTop: 4 }}>{value}</div>
    {sub && <div className="mono" style={{ fontSize: 11, color: "var(--text-4)" }}>{sub}</div>}
  </div>;
}
export function Chip({ tone = "var(--text-3)", children }: { tone?: string; children: React.ReactNode }) {
  return <span className="mono" style={{ fontSize: 10, letterSpacing: ".08em", padding: "2px 6px",
    borderRadius: 5, color: tone, border: `1px solid ${tone}`, textTransform: "uppercase" }}>{children}</span>;
}
export function CellTiles({ item }: { item: FleetItem }) { /* … per description … */ }
```

- [ ] **Step 3: Typecheck**

Run: `cd web && npx tsc -b`
Expected: no type errors.

- [ ] **Step 4: Commit**

```bash
git add web/src/v2/components/Ring.tsx web/src/v2/components/Atoms.tsx
git commit -m "feat(web): v2 command UI atoms (ring, stat tile, bar, chip, cell tiles)"
```

---

### Task 13: Command sub-panels (fleet rail, stage, range, aside)

**Files:**
- Create: `web/src/v2/components/CommandFleetRail.tsx`
- Create: `web/src/v2/components/CommandStage.tsx`
- Create: `web/src/v2/components/CommandRange.tsx`
- Create: `web/src/v2/components/CommandAside.tsx`

**Interfaces:**
- Consumes: `Base[]`/`BasePack` (Task 10), `Ring`/`Atoms` (Task 12), `estimatePackRange`/`minRange`/`formatRangeLine` (`../range`), `RangeParams`/`SEED_RANGE_PARAMS`, `Trip`/`classifyTrip` (Task 11), `deltaMv`/`isCharging`.
- Produces:
  `<CommandFleetRail bases stageBaseId onStage />`,
  `<CommandStage base rangeParams unit tempF />`,
  `<CommandRange base rangeParams trips onEditTrips />`,
  `<CommandAside bases now onOpen />`.

- [ ] **Step 1: Implement CommandFleetRail**

Create `web/src/v2/components/CommandFleetRail.tsx`: for each `Base`, a clickable header (status dot colored by `status` → `--ok`/`--warn`/`--text-4`, "Base {id}", a `Chip` for the status tag IN USE/CHARGING/BACKUP/SPARES, and a "STAGE" chip when `base.id === stageBaseId`); two pack rows (letter, SOC `Bar`, SOC %, SOH dot). Clicking the header OR a row calls `onStage(base.id)`. Disconnected packs render muted (no %). Signature:

```tsx
export function CommandFleetRail({ bases, stageBaseId, onStage }: {
  bases: Base[]; stageBaseId: string; onStage: (id: string) => void;
}) { /* … 252px rail, token-styled … */ }
```

- [ ] **Step 2: Implement CommandStage**

Create `web/src/v2/components/CommandStage.tsx`: a thermal banner (amber `--warn`) shown only when any connected pack has `temp_c >= 44`; a MAIN STAGE card titled "Base {id}" + status `Chip` + role text; the two packs side by side, each with name + SOH dot, `<Ring size={132}>`, a power·current line (`{power_w}W · {current_a}A`), a 2×2 `StatTile` grid (CAPACITY `{remaining_ah}/{full_charge_ah} Ah`, TEMP in `tempF` unit, HEALTH `{soh}%`, CYCLES `{cycles}`), then `<CellTiles item>`; below the packs, three flow tiles: DRAW NOW / CHARGE IN / FLOW (from `power_w`/charging), EST. RUNTIME (from the range active-hours band) or TIME TO FULL (`eta_full_min`) when charging, and DRIVEN TODAY → "—" (stub). Convert temp with a small helper `fmtTemp(c, tempF)`. Signature:

```tsx
export function CommandStage({ base, rangeParams, tempF }: {
  base: Base; rangeParams: Map<string, RangeParams>; tempF: boolean;
}) { /* … */ }
```

- [ ] **Step 3: Implement CommandRange**

Create `web/src/v2/components/CommandRange.tsx`: computes each connected pack's `estimatePackRange(isCharging(item), item.remaining_ah, params)` (params from `rangeParams.get(address) ?? SEED_RANGE_PARAMS`); if any pack is charging OR no pack yields a range, render "Charging — see recharge plan" (empty range state); else `minRange([...])` bounds a big miles readout + `formatRangeLine` band + typical, and each `Trip` gets a go/tight/no-go `Chip` via `classifyTrip`. An "Edit trips" affordance calls `onEditTrips`. Signature:

```tsx
export function CommandRange({ base, rangeParams, trips, onEditTrips }: {
  base: Base; rangeParams: Map<string, RangeParams>; trips: Trip[]; onEditTrips: () => void;
}) { /* … */ }
```

- [ ] **Step 4: Implement CommandAside**

Create `web/src/v2/components/CommandAside.tsx`: RECHARGE PLAN (across all bases, packs with `eta_full_min != null` and `soc < 99`, each: label, SOC `Bar`, "≈ {eta} to full", "ready by {clock(now + eta)}"); FLEET HEALTH (stacked `Bar` over all 8 packs by SOH band good ≥90 `--ok` / fair 80–89 `--warn` / degraded <80 `--live`, + worst-pack note); TODAY'S ROUTE stub panel with "Open Journey →" and "History →" buttons calling `onOpen(view)`. Signature:

```tsx
export function CommandAside({ bases, now, onOpen }: {
  bases: Base[]; now: number; onOpen: (v: "journey" | "history") => void;
}) { /* … 340px … */ }
```

- [ ] **Step 5: Typecheck**

Run: `cd web && npx tsc -b`
Expected: no type errors.

- [ ] **Step 6: Commit**

```bash
git add web/src/v2/components/CommandFleetRail.tsx web/src/v2/components/CommandStage.tsx \
        web/src/v2/components/CommandRange.tsx web/src/v2/components/CommandAside.tsx
git commit -m "feat(web): v2 command sub-panels (fleet rail, stage, range, aside)"
```

---

### Task 14: Command view + live data wiring

**Files:**
- Create: `web/src/v2/views/CommandView.tsx`
- Create: `web/src/v2/useFleetData.ts` (store + WS + REST fallback + synced configs, adapted from v1 App.tsx)
- Modify: `web/src/v2/App.tsx` (mount `CommandView`; feed `live`/`gps`/`synced` to TopBar)

**Interfaces:**
- Consumes: `createStore`/`connectLive`/`getFleet` (v1 data layer), `getRangeConfig`/`selectRangeParams`, `groupBases`, the Command sub-panels, `useV2Settings`.
- Produces:
  `useFleetData()` → `{ items: FleetItem[]; staleAddrs: Set<string>; live: boolean; gps: boolean; rangeParams: Map<string,RangeParams>; now: number }`;
  `<CommandView data settings stageBaseId onStage onOpen trips onEditTrips />`.

- [ ] **Step 1: Implement useFleetData**

Create `web/src/v2/useFleetData.ts` — reuse v1's proven pattern (`createStore`, `useSyncExternalStore`, `connectLive`, the 90s `STALE_MS`, the 10s REST fallback, and a periodic `getRangeConfig`+`selectRangeParams` poll). This is a v2-local hook mirroring the relevant slices of `web/src/App.tsx:42-158` (do NOT import from v1 App; copy the logic into this hook):

```ts
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { createStore } from "../store";
import { connectLive } from "../ws";
import { getFleet, getRangeConfig } from "../api";
import { selectRangeParams, type RangeParams } from "../range";
import type { FleetItem } from "../types";

const STALE_MS = 90_000;
const REST_FALLBACK_MS = 10_000;

export function useFleetData() {
  const store = useRef(createStore()).current;
  const v = useSyncExternalStore(store.subscribe, store.getVersion);
  const [live, setLive] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [rangeParams, setRangeParams] = useState<Map<string, RangeParams>>(new Map());

  useEffect(() => connectLive((f) => store.applySnapshot(f), store.applySample, setLive), [store]);
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);
  useEffect(() => {
    if (live) return;
    const t = setInterval(() => { getFleet().then((r) => store.applySnapshot(r.fleet)).catch(() => {}); }, REST_FALLBACK_MS);
    return () => clearInterval(t);
  }, [live, store]);
  useEffect(() => {
    let alive = true;
    const load = () => getRangeConfig().then((r) => { if (alive) setRangeParams(selectRangeParams(r.configs)); }).catch(() => {});
    load(); const t = setInterval(load, 60_000);
    return () => { alive = false; clearInterval(t); };
  }, []);

  const items = useMemo(
    () => Object.values(store.getFleet()).sort((a, b) => (a.alias ?? "").localeCompare(b.alias ?? "")),
    [store, v]);
  const staleAddrs = useMemo(
    () => new Set(items.filter((i) => now - i.ts_ms > STALE_MS).map((i) => i.address)), [items, now]);
  const gps = useMemo(
    () => items.some((i) => !staleAddrs.has(i.address) && i.lat != null), [items, staleAddrs]);

  return { items, staleAddrs, live, gps, rangeParams, now };
}
```

- [ ] **Step 2: Implement CommandView**

Create `web/src/v2/views/CommandView.tsx` — `groupBases(items, staleAddrs)`, resolve the staged base (`bases.find(id===stageBaseId) ?? bases[0]`), lay out the 3-column grid (`252px | 1fr | 340px`, single-column on mobile via a `mobile` prop with flex `order`), and render `CommandFleetRail` + `CommandStage` + `CommandRange` + `CommandAside`. Owns saved-trips state via `useLocalStorage("bmsmon-v2-trips", …, tripsCodec)` and a minimal edit affordance (a prompt-based add/remove is acceptable for Phase 1; keep it small).

```tsx
export function CommandView({ mobile }: { mobile: boolean }) {
  const data = useFleetData();
  const [settings] = useV2Settings();
  const [stageBaseId, setStageBaseId] = useLocalStorage<string>(/* session default 2012 */);
  const [trips, setTrips] = useLocalStorage<Trip[]>("bmsmon-v2-trips", () => [], tripsCodec);
  const bases = useMemo(() => groupBases(data.items, data.staleAddrs), [data.items, data.staleAddrs]);
  const staged = bases.find((b) => b.id === stageBaseId) ?? bases[0];
  /* … render grid … */
}
```

(If `bases` is empty — before the first snapshot — render the CONNECTING placeholder like v1 App.tsx:236-243.)

- [ ] **Step 3: Wire into App**

In `web/src/v2/App.tsx`: replace the Command placeholder branch with `<CommandView mobile={mobile} />`, and lift `useFleetData` up (or expose `live`/`gps` via a light context / prop) so `TopBar` shows real `live`/`gps`. Simplest: call `useFleetData` in App, pass `live`/`gps` to `TopBar`, and pass the data down to `CommandView` as a prop instead of it calling the hook again (avoid two live stores). Refactor `CommandView` to accept `data` as a prop.

- [ ] **Step 4: Verify against live data**

Run: `cd web && npm run build && npm run dev`
With the dev proxy pointing at a running server (or prod via a temporary proxy), open `http://localhost:5173/v2/`: the Command view shows the real fleet grouped into bases; clicking a base/pack promotes it to the stage and the rings/cells/tiles/range all follow; the TopBar LIVE pill reflects the WS; charging packs show a recharge state in the range slot; a pack with no `cells` yet still renders the min/max fallback in the cell section.

- [ ] **Step 5: Commit**

```bash
git add web/src/v2/views/CommandView.tsx web/src/v2/useFleetData.ts web/src/v2/App.tsx
git commit -m "feat(web): v2 Command view wired to live fleet + range data"
```

---

### Task 15: Full suite + end-to-end verification

**Files:** none (verification + docs)

- [ ] **Step 1: Run the whole web suite**

Run: `cd web && npm test && npx tsc -b`
Expected: all vitest green, no type errors.

- [ ] **Step 2: Run the server suite**

Run: `docker compose -f server/docker-compose.dev.yml up -d && cd server && .venv/bin/python -m pytest`
Expected: all green (including `test_cells.py`).

- [ ] **Step 3: Build both bundles**

Run: `cd web && npm run build`
Expected: `dist/index.html` (v1) + `dist/v2/index.html` (v2) + shared `dist/assets`.

- [ ] **Step 4: Verify v1 untouched + v2 served**

Serve `dist` (e.g. run the server with `BMSMON_WEB_DIST=$(pwd)/web/dist`, or `python -m http.server` in `dist`) and confirm: `GET /` still renders v1 unchanged; `GET /v2/` renders the v2 shell; theme/mobile toggles work; Command binds live data. Use the `verify` skill to drive this end-to-end.

- [ ] **Step 5: Update docs + roadmap**

Mark Phase 1 done in `docs/superpowers/specs/2026-07-12-webui-v2-roadmap.md` progress log; add a Phase 1 note to the project `CLAUDE.md` "WebUI v2" section (served at `/v2/`, shell + Command live, cell pipeline landed). Commit:

```bash
git add docs/superpowers/specs/2026-07-12-webui-v2-roadmap.md CLAUDE.md
git commit -m "docs: mark WebUI v2 Phase 1 (foundation + Command) complete"
```

---

## Self-Review (completed)

**Spec coverage:** Shell (nav/topbar/theme/mobile/state) → Tasks 5–9; tokens → Task 5; Command layout + all sub-panels → Tasks 10–14; range/trip formulas reuse `range.ts` → Tasks 11,13; cell pipeline (android→server→web) → Tasks 1–3,12; `/v2/` build+deploy → Task 4; GPS bits stubbed (DRIVEN TODAY, route map) → Tasks 13,14; placeholders for the other five views → Task 9. Every spec §4–§8 item maps to a task.

**Deferred (correctly out of Phase 1):** Fleet Health/Journey/History/Alerts/Settings live views, GPS aggregation, charge-session log — placeholdered, tracked in the roadmap.

**Type consistency:** `V2View`, `Base`/`BasePack`/`BaseStatus`, `RangeParams`/`PackRange`, `Trip`/`TripVerdict`, `ThemeMode`, `V2Settings` are defined once and consumed with the same signatures across tasks. `cells: number[] | null` is consistent across server (`ARRAY[...]`), WS (`model_dump`), and web (`Sample.cells`).

**Note on the spec's build detail:** the spec §2.2 described a separate `vite.v2.config.ts` with `base:'/v2/'`; this plan uses the simpler equivalent — a single multi-page build (second rollup input, shared `base:'/'`, assets under `/assets`), same `/v2/` serving with zero server/Docker/package.json change. The spec will be updated to match.
