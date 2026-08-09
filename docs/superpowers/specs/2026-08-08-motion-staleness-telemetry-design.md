# Motion Reading Timestamp in Uploaded Telemetry — Design

**Date:** 2026-08-08
**Status:** Approved by user (approach and design approved in conversation).
**Extends:** `2026-08-08-motion-telemetry-design.md` (the three motion columns this closes a gap in).

## Goal

Make a row's `motion_still = false` **explainable from the server**: was the gate failing open on a
stale reading, or mid-debounce on a fresh one? Today those states store identically. As a second
win, make Play Services' bursty delivery **measurable from prod SQL** — the input the open
`MOTION_STALE_MS` tuning decision needs — instead of only from logcat on a device that is reachable
over ADB just while it is on home Wi-Fi.

## Why

The first production rows after the motion-telemetry deploy (2026-08-08 19:55) read
`motion_activity=STILL, motion_confidence=100, motion_still=false` with zero discharge — a confident
still reading with the gate open, minutes after restart and well past the 3-reading debounce. That
is consistent with the documented bursty-delivery limitation (a reading older than
`MOTION_STALE_MS = 150 s` fails open), **but the stored fields cannot distinguish it from "debounce
not yet met", because the reading's age is not uploaded.**

`foldMotion` (`model/BatterySaver.kt`) decides staleness from `MotionReading.atMs`; the row stores
the reading's activity and confidence but not *when the reading was taken*. So `STILL@100` in a row
says nothing about whether the gate saw it as evidence or as history.

The handover's instruction: close this **between** diagnostic cycles, not during one — the same
lesson that produced the motion columns themselves.

## What is recorded

One nullable field on the wire row (`SampleJson`) and one nullable column on `samples`:

| field | type | meaning |
|---|---|---|
| `motion_at_ms` | `Long?` / `bigint` | `MotionReading.atMs` — wall-clock ms when the reading was cached, `System.currentTimeMillis()`, the **same clock as the sample's `ts_ms`** |

**Raw timestamp, not a derived age or staleness flag — deliberately:**

- **Age is exactly derivable:** `ts_ms - motion_at_ms`, no clock skew (same device clock). A
  derived `motion_age_s` would vary on every row (worse gzip than a repeated constant) and buy
  nothing.
- **A boolean `motion_stale` flag would bake today's 150 s constant into stored history.** The
  `MOTION_STALE_MS` retune is a live open decision; rows flagged under the old constant could not
  be reinterpreted under a new one. The timestamp stays true under any constant.
- **The timestamp is the reading's identity.** `atMs` is the gate's own dedup key
  (`MotionGate.lastConfidentAtMs`), so distinct `motion_at_ms` values identify individual readings.
  That makes the Play Services delivery-gap distribution a `SELECT DISTINCT` away, and makes the
  whole gate **replayable**: fold the distinct readings in row order and validate the result
  against the stored `motion_still` at each step.

**No rounding.** The value's identity role is the point; truncation would merge readings and break
replay.

**Null semantics mirror `motion_activity`/`motion_confidence` exactly:** the three go null together,
and only when there is no motion reading at all (AR unavailable, permission denied, motion sensing
not running). `motion_still` stays always-populated on this client, as today. `CloudJson.json` is
`explicitNulls = false`, so the absent case still costs zero bytes.

## Android

Three touch points, no logic change — this is instrumentation only, the gate is untouched:

- `MonitorEngine.onPoll`: already computes `val (motion, gate) = applyGpsGate(now)` under one lock;
  additionally pass `motion?.atMs` to `reporter.report(...)`.
- `TelemetryReporter.report(...)`: gains `motionAtMs: Long?`, threaded through unchanged.
- `CloudJson`: `SampleJson` gains `val motion_at_ms: Long? = null` beside the other motion fields;
  `sampleJson(...)` gains the matching parameter.

No non-finite guard — it is a `Long`, not a float.

## Server

Three touch points, following the motion-columns pattern exactly:

```sql
ALTER TABLE samples ADD COLUMN IF NOT EXISTS motion_at_ms bigint;
```

Idempotent, runs on pool creation — the column lands automatically when the container restarts, no
migration step.

`SampleIn` (`server/app/models.py` — the Pydantic twin, not to be conflated with the Kotlin
`SampleJson`) gains `motion_at_ms: int | None = None` **with a clamp-to-null validator**: values
outside a plausible epoch-ms range (positive, below 4 102 444 800 000 = 2100-01-01) become `None`.
Same rationale as the existing `motion_confidence` validator: Python ints are unbounded, the batch
insert is one set-based statement, and an out-of-range value reaching `bigint` would 500 the
**whole batch**, which the phone treats as poison and drops — losing real telemetry to one bogus
field.

`queries.py`: add `motion_at_ms` to `_COLS` and the insert column list — the same generic
`sample_row()` mechanism every late column uses.

## Accepted trade-off: wire cost

Same duplication acceptance as the parent spec: motion is device-level, rows are per-pack, so the
value is written 8×. Raw cost ≈ +28 B/row (`"motion_at_ms":1754700000000,`), but the value repeats
byte-identically across every row of a batch until a new reading arrives (readings ~every 5–30 s,
batches ~every 12–15 s), so gzip collapses nearly all of it. The **measured** wire cost of the
motion fields is already a pending real-world task (handover §4); it will capture this field's
contribution in the same measurement rather than getting a separate synthetic estimate.

## Out of scope

- **Any WebUI change** — SQL diagnostics only, same as the parent spec.
- **Backfill** — rows before this ships stay null; the data did not exist.
- **Local Room** — motion is cloud-only today and stays so; on-device diagnosis already has logcat.
- **`motion_run` (the debounce counter)** — derivable by replaying the distinct readings against
  `motion_still`; a fourth column can be added later if replay proves too awkward in practice.
- **Any change to `foldMotion`/`MotionGate`** — instrumentation must not alter the behaviour being
  instrumented.

## Testing

- `CloudJsonTest`: a sample with a motion reading encodes `motion_at_ms`; a sample without omits it
  from the JSON entirely (the omission is what keeps the absent case free, so it gets a real
  assertion).
- Server: ingest round-trips the column; a body without it still ingests (older client); the
  validator clamps an out-of-range value to null instead of rejecting the batch.
- On-device after deploy: prod rows carry `motion_at_ms`, and `ts_ms - motion_at_ms` reproduces the
  staleness behaviour the logcat traces showed.

## What this unlocks

```sql
-- Stale fail-open vs debounce-not-met — the exact ambiguity in the 19:55 rows:
SELECT to_timestamp(ts_ms/1000), motion_activity, motion_confidence, motion_still,
       (ts_ms - motion_at_ms) / 1000.0 AS reading_age_s
FROM samples WHERE motion_still = false AND motion_activity = 'STILL'
ORDER BY ts_ms;

-- Play Services delivery-gap distribution (feeds the MOTION_STALE_MS decision):
SELECT gap_s, count(*) FROM (
  SELECT (motion_at_ms - lag(motion_at_ms) OVER (ORDER BY motion_at_ms)) / 1000.0 AS gap_s
  FROM (SELECT DISTINCT motion_at_ms FROM samples WHERE motion_at_ms IS NOT NULL) r
) g WHERE gap_s > 0 GROUP BY 1 ORDER BY 1;
```

## Deploy

Order is load-bearing, same as the parent feature: **server first, then the APK.** A new phone
against the old server has its unknown keys silently ignored (`SampleIn` defaults to
`extra="ignore"`) and would read as an Android failure. Then `adb install -r`, `am start`, confirm
the process, and confirm prod rows carry the new field. Update `CLAUDE.md`'s motion-telemetry
section to record the gap as closed.
