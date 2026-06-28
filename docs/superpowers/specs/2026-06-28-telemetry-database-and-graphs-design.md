# Telemetry Database & Battery Graphs — Design

**Date:** 2026-06-28
**Project:** bmsmon Android app (`android/`)
**Status:** Approved design, pending implementation plan

## Problem

Telemetry is currently appended to a flat CSV (`TelemetryLogger` → `usage_log.csv`,
size-capped with one rolling archive). The CSV stores only a thin decoded slice
(`timestamp_ms, name, address, state, soc, current_a, power_w, voltage_v, regen`) and
throws away the richer fields every 0x13 frame already carries (SOH, full-charge capacity,
cycle count, temperatures, cell voltages). It is also awkward to query, can't be charted
in-app, and has no notion of "a monitoring session."

We want to:

1. Replace the CSV with a proper on-device database.
2. Capture the full telemetry frame, including the fields that are the *real* battery-aging
   signals.
3. Surface in-app **graphs**, primarily to watch whether a pack delivers less power / sags
   more as it ages.
4. Keep a **raw-frame debug log** so a bad decode (like today's spurious 0% report) can be
   re-investigated after the fact.
5. Collapse gaps in time — the app is not running 24/7 and not all packs are monitored
   continuously, so the history must not render long empty stretches.

## Goals

- Room-backed storage replacing the CSV writer entirely (straight cutover, no parallel CSV).
- A schema that separates **full-resolution recent samples** (prunable) from **permanent
  per-session rollups** (tiny, kept forever) so years of aging history stay cheap.
- A one-time import of the existing CSV history into the database.
- A capped raw-frame table for debugging.
- Three core graphs (peak power, internal-resistance/sag, capacity & SOH) on a per-battery
  History view, plus a fleet History screen overlaying all packs.
- Gaps collapse naturally: aging-trend x-axes are *session index*, not wall-clock.

## Non-Goals

- No change to the BLE protocol, command set, or safety rules (still read-only 0x13 etc.).
- No cloud sync / export-to-server. Local DB only. (CSV-pull workflow is superseded; a future
  "export DB" action is possible but not built here.)
- No change to the monitoring engine's lifecycle (foreground service, stage logic untouched
  beyond the logging call).
- No predictive analytics / health scoring beyond plotting the trends.

## Decisions

| Decision | Choice |
|----------|--------|
| Storage engine | **Room (SQLite)** — type-safe DAOs, `Flow` queries into Compose, first-class migrations |
| Schema | **3 tables**: `samples` (full-res, pruned), `sessions` (rollups, permanent), `raw_frames` (capped) |
| Raw frames | **Capped table**, every frame's raw hex, pruned to **7 days or 20 MB**, oldest-first; `reason` tag |
| Charting | **Custom Compose Canvas** (no chart library) — matches the hand-rolled gauge aesthetic |
| Existing CSV data | **Import once** into `samples` + derived `sessions` on first launch; CSV files left on disk |
| CSV writer | **Removed** — straight cutover to the DB |
| Graph placement | **Per-battery Detail → History** section **+** a new **fleet History** screen |
| Aging bucket | **Per monitoring session** (a gap > 10 min, or a disconnect→reconnect, starts a new session) |
| Sample retention | Full-res `samples` pruned after **14 days**; `sessions` kept forever |

## Architecture

### Storage layer (Room)

A new `dev.joely.bmsmon.data.db` package:

- `BmsDatabase` (RoomDatabase) — singleton owned by `BmsApp`, opened off the main thread.
- Entities: `SampleEntity`, `SessionEntity`, `RawFrameEntity`.
- DAOs: `SampleDao`, `SessionDao`, `RawFrameDao` — suspend writes, `Flow` reads.
- `TelemetryRepository` — the single write/read facade the rest of the app uses. Replaces
  `TelemetryLogger`. Responsibilities: ingest a sample (insert sample row + update/close/open
  session + insert raw frame), run retention pruning, expose query `Flow`s for the UI, and
  run the one-time CSV import.

### Schema

**`samples`** — one row per poll per battery (full-res, prunable):

```
id (PK)            address           ts_ms            session_id (FK→sessions)
state              soc               current_a        power_w          voltage_v
temp_c             mosfet_temp_c     soh              full_charge_ah   remaining_ah
cycles             cell_min_v        cell_max_v       regen            link_event (nullable)
```

- `link_event` is null for telemetry rows; for connect/disconnect events it carries
  `Connected`/`Disconnected` and the telemetry columns are null (mirrors today's CSV
  `event()` rows, but typed).
- Indices: `(address, ts_ms)`, `(session_id)`.
- **Pruned after 14 days.**

**`sessions`** — one row per monitoring run, per battery (permanent rollups):

```
id (PK)            address            start_ms          end_ms          sample_count
peak_power_w       p95_power_w        mean_power_w      peak_current_a  peak_regen_w
energy_wh          soc_start          soc_end           min_soc         max_soc
min_voltage_under_load    est_internal_resistance_mohm
soh_end            full_charge_ah_end cycles_end        max_temp_c
```

- A session is **per battery address**. The first sample whose gap since that address's
  previous sample exceeds `SESSION_GAP_MS` (10 min), or which follows a `Disconnected`
  link event, **closes** the current session and **opens** a new one.
- Rollups are maintained incrementally as samples arrive (running peak/sum/count) and
  finalized on close; an open session is finalized on app shutdown / next-sample-after-gap.
- `est_internal_resistance_mohm`: see *Aging metrics* below.
- **Kept forever** (a few rows per battery per day → trivially small).

**`raw_frames`** — capped raw-hex debug log:

```
id (PK)   address   ts_ms   hex (the raw BLE response bytes)   reason
```

- `reason` ∈ `periodic` (normal frame), `decode_fail` (parse returned null),
  `realign` (header had to be re-found, i.e. prepended stale bytes).
- **Capped at 7 days or 20 MB**, whichever is hit first; pruned oldest-first on a periodic
  housekeeping pass.

### Ingest plumbing

Today `MonitorEngine` receives a parsed `Telemetry` and calls
`logger.log(addr, t, now, regen)`; the raw bytes from `BleSession.poll()` are discarded
after parsing. Changes:

- `BleSession.poll()` already returns the raw `ByteArray`. Thread that raw buffer (alongside
  the parsed `Telemetry`) through `MonitorEngine` into `TelemetryRepository.ingest(...)`.
- `TelemetryRepository.ingest(address, telemetry, rawFrame, reason, regen, tsMs)`:
  1. resolve/advance the session for `address`,
  2. insert the `samples` row (full fields),
  3. update the session's running rollups,
  4. insert the `raw_frames` row,
  5. opportunistically run retention pruning (throttled, not every call).
- Connect/disconnect events keep a dedicated `TelemetryRepository.logLink(...)` path writing
  a `samples` row with `link_event` set (and closing the open session on `Disconnected`).
- `TelemetryLogger` and its CSV files are **removed** from the write path. The Settings
  "logging on/off", "clear log", and log-size readouts are repointed at the repository
  (DB size, `clear()` = wipe tables).

### CSV import (one-time)

On first launch after upgrade (guarded by a persisted `csv_imported` flag in the existing
settings/datastore):

- Read `usage_log.csv` then `usage_log.1.csv` (archive) if present, oldest first.
- Parse each row, insert a `samples` row (CSV-absent fields — temp, soh, capacities, cycles,
  cells — stored null), and derive `sessions` using the same 10-min gap logic.
- Raw frames are **not** backfilled (the CSV never had them).
- CSV files are **left on disk** (not deleted) as a manual safety copy.
- Import runs in the background; the app is usable meanwhile.

### UI

- **Per-battery Detail screen** gains a **History** section rendering the three core graphs
  scoped to that pack, reading session/sample `Flow`s for that address.
- **New fleet History screen** (`ui/history/`), reachable from Home (top-bar/menu entry),
  overlays every pack's trend on shared axes (e.g. all packs' internal-resistance lines
  together) so an outlier pack is obvious. A battery legend toggles series.
- **Charts** live in a small reusable `ui/charts/` module: a `LineChart` composable taking
  `List<Point>` + series metadata, themed with `Bm` color tokens, JetBrains Mono axis
  labels. The x-axis for aging trends is **session index** (evenly spaced), with the session
  start date shown as a label — so gaps in calendar time collapse to adjacent points.

## Graphs

### Graph 1 — Peak power per session (the primary ask)

One point per session: **peak** and **p95** discharge watts. Answers "how hard does this
chair pull" and gives a rough aging hint.

> **Caveat (surfaced in the design, optionally noted in-UI):** peak power per session is
> driven by *demand*, not just battery health — a gentle day reads low even from a healthy
> pack. So this trend alone is a noisy aging proxy. Graphs 2 and 3 isolate aging without the
> demand confound.

### Graph 2 — Internal resistance / voltage-sag trend (recommended)

Per session, estimate effective internal resistance from the relationship between load
current and voltage sag (ΔV/ΔI — volts dropped per amp drawn). Stored as
`est_internal_resistance_mohm`. **This is the clean aging signal**: rising internal
resistance = aging cells, and unlike peak-W it is independent of how hard the chair was
driven. Plotted one point per session.

Estimation method: within a session, regress sampled `voltage_v` against `current_a`
(discharge samples) — the slope approximates pack resistance; the magnitude of sag at the
session's peak current gives `min_voltage_under_load`. Sessions with too little current
spread are flagged low-confidence (rendered faded / skipped) rather than producing noise.

### Graph 3 — Capacity & SOH vs cycles (recommended)

Plot BMS-reported **full-charge capacity (Ah)** and **SOH (%)** against **cycle count**
(x-axis) — the textbook battery-aging metric. Already present in every frame; was discarded
by the CSV. Slow-moving, high signal-to-noise. One point per session (using each session's
end-of-session values).

### Optional extra — Per-session timeline

Power & SOC across a single drive (x-axis = time within that one session). Low cost, useful
for inspecting one specific run. Built if time allows; not required for the core deliverable.

## Constants (initial; tunable)

| Constant | Default | Meaning |
|----------|---------|---------|
| `SESSION_GAP_MS` | 10 min | Gap (or a disconnect) that closes a session and opens the next |
| `SAMPLE_RETENTION_DAYS` | 14 | Age after which full-res `samples` rows are pruned |
| `RAW_FRAME_RETENTION_DAYS` | 7 | Age cap for `raw_frames` |
| `RAW_FRAME_MAX_BYTES` | 20 MB | Size cap for `raw_frames` (whichever limit hits first) |
| `IR_MIN_CURRENT_SPREAD_A` | TBD in plan | Minimum current range in a session to trust the resistance estimate |

## Testing

- **Pure-logic unit tests** (the project's existing JVM test style, e.g. `FleetLogicTest`):
  - session segmentation (gap > 10 min and disconnect both split; back-to-back samples don't),
  - rollup aggregation (peak/p95/mean/energy correct over a synthetic sample stream),
  - internal-resistance estimation on synthetic V/I data (known slope recovered; low-spread
    session flagged low-confidence),
  - CSV-row → `samples` mapping (absent fields null; malformed rows skipped),
  - retention pruning (rows older than the cap removed; sessions never pruned).
- **Room DAO tests** (in-memory database) for insert/query/prune.
- Repository ingest test: a sequence of samples produces the expected rows across all three
  tables, including a forced `decode_fail` raw frame.

## Risks & Mitigations

- **Raw-frame storage growth.** Mitigated by the dual time+size cap with oldest-first pruning;
  housekeeping throttled so it doesn't run every sample.
- **Internal-resistance noise.** Sessions with insufficient current spread are low-confidence
  and de-emphasized rather than charted as if reliable.
- **Import correctness.** Import is idempotent (guarded flag) and non-destructive (CSV kept);
  malformed CSV rows are skipped, not fatal.
- **Write-path latency on the BLE thread.** Repository writes are suspend/off-main; ingest must
  not block the poll loop.

## Open Items for the Implementation Plan

- Exact `IR_MIN_CURRENT_SPREAD_A` threshold and the resistance regression details.
- Room schema version / migration wiring and where the `csv_imported` flag lives.
- Fleet History screen entry point (top-bar icon vs overflow menu).
- Whether the optional per-session timeline ships in the first cut.
