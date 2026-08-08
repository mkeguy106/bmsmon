# Motion State in Uploaded Telemetry — Design

**Date:** 2026-08-08
**Status:** Approved by user (design presented in conversation; "write it up").

## Goal

Make the motion gate's behaviour reconstructible **from the server**, instead of only from `logcat`
on a device that is reachable over ADB just while it is on home Wi-Fi.

## Why

Diagnosing the motion gate (2026-08-06 → 08) took three days, and the cost was almost entirely
observability. The uploaded sample row carries no motion state, so the server could show *whether*
GNSS stayed on but never *why*. Every answer required ADB, and:

- The device is on the user's wheelchair; ADB reaches it only at home. During the vehicle outing —
  the one measurement that mattered — it was on an iPhone hotspot and unreachable.
- `MotionSource` logs each reading, but logcat's default ring buffer is **256 KiB** and rotated the
  evidence away twice. Surviving the 2026-08-08 outing required manually raising it to 32 MiB and
  clearing it at departure. That is not a repeatable diagnostic.
- Both real defects were invisible from the server. `UNKNOWN@41` blocking the gate looked identical
  to "AR is dead", and the debounce behaving as N=1 looked identical to correct operation.

With these fields, both would have been a single SQL query on day one.

## What is recorded

Three nullable fields per sample:

| field | type | meaning |
|---|---|---|
| `motion_activity` | `String?` | most probable detected activity, e.g. `STILL`, `IN_VEHICLE`, `UNKNOWN` |
| `motion_confidence` | `Int?` | that activity's confidence, 0–100 |
| `motion_still` | `Boolean?` | the **gate's** verdict — `MotionGate.still` |

**All three, not a subset.** The first two are the sensor's evidence; the third is the app's
conclusion, and it is **not derivable from them** because the debounce (`STILL_DEBOUNCE_N = 3`)
carries state across readings and uncertainty *holds* the previous verdict. Recording only the
reading would have shown `STILL@100` without revealing the gate was still open; recording only the
verdict would have shown the gate never closing without revealing `UNKNOWN@41` was the reason.

**Activity as a readable string, not the raw `DetectedActivity` int.** `motion_activity = 'IN_VEHICLE'`
is greppable in SQL; `= 0` needs a lookup table nobody will remember at 2am. `MotionSource` already
has an `activityName()` mapper for its logging (`motion/MotionSource.kt:153`) — reuse it rather than
duplicating the mapping. **It is currently a private top-level function**, so it needs widening to
`internal` (or moving onto the `MotionSource` companion) for the upload path to call it; do not copy
the `when` block, since two copies would drift.

This also implies **`MotionReading` must carry the activity name**. Today it holds only
`still: Boolean`, `confidence: Int`, `atMs: Long` — the activity type is mapped for the log line and
then discarded. Add the name to `MotionReading` at the point the reading is constructed, so the
upload path reads it from the same object the gate does rather than re-deriving it. `MotionReading`
lives in the pure `model/BatterySaver.kt`, so the field must be a plain `String`, not an Android
type — the mapping to a name happens in `MotionSource`, which is where the Android dependency
already lives.

## Android

Three fields on `SampleIn` (`cloud/CloudJson.kt`), following the established `gps_accuracy_m` /
`eta_full_min` pattern exactly:

```kotlin
val motion_activity: String? = null,
val motion_confidence: Int? = null,
val motion_still: Boolean? = null,
```

`CloudJson.json` is configured `explicitNulls = false`, so when motion sensing is unavailable —
permission denied, AR unsupported, no reading yet — the fields are simply **absent from the
payload**. No `motionEnabled` flag is needed, and installs without the permission pay **zero bytes**.

`MonitorEngine` already holds both halves where it calls `reporter.report()`: `motionSource.current()`
for the reading and `motionGate.still` for the verdict. This threads three more arguments through
`TelemetryReporter.report()` and `CloudJson.sampleJson()`; no new state, no new lifecycle.

**Non-finite guard is not needed here** — `confidence` is an `Int` and the other two are a `String`
and a `Boolean`, so the existing `finiteOrNull()` treatment for floats does not apply.

## Server

Three nullable columns, added the way every other late column in this schema was
(`server/app/db/schema.sql`, alongside the existing `ALTER TABLE samples ADD COLUMN IF NOT EXISTS`
lines at ~110-111):

```sql
ALTER TABLE samples ADD COLUMN IF NOT EXISTS motion_activity   text;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS motion_confidence smallint;
ALTER TABLE samples ADD COLUMN IF NOT EXISTS motion_still      boolean;
```

Idempotent SQL run on pool creation, so **the columns land automatically when the container
restarts — there is no separate migration step**. The ingest path maps the three fields alongside
the existing optional ones; all remain nullable so older clients keep working unchanged.

`smallint` for confidence: the value is 0–100 and `smallint` is the honest width.

## Accepted trade-off: the reading is duplicated across packs

Motion is a **device-level** fact, but `samples` rows are **per-pack**, so with 8 packs each reading
is written 8 times.

The clean alternative — a separate device-state table with its own endpoint and query paths — is
substantially more work for data whose only consumer is ad-hoc SQL. Because the duplicated values
are identical within an upload batch, gzip (already applied to the whole body) collapses them.

**This is an assumption, not a measurement.** The implementation must check the actual wire cost
against current batch sizes and record the number. If the increase is material, the fallback is to
populate the fields **only on the staged base's rows** rather than all packs — the staged base is
the one whose behaviour anyone is diagnosing.

## Out of scope

- **Any WebUI change.** This is diagnostic data for SQL, not something the dashboard needs. Adding
  it there would be scope for its own sake.
- **Backfill.** The columns are null for everything uploaded before this ships. That is correct;
  the data did not exist.
- **A device-state table.** See the trade-off above — revisit only if the measured wire cost says so.

## Testing

- `CloudJsonTest` (existing): a sample **with** motion fields encodes all three; a sample **without**
  omits them entirely from the JSON rather than emitting `null`s — that omission is what makes the
  feature free when unused, so it needs a real assertion, not an assumption.
- Server tests: ingest round-trips the three columns, and a body **without** them still ingests
  (older clients must not break).
- On-device: after installing, confirm rows arriving at the server carry non-null
  `motion_activity`/`motion_confidence`/`motion_still`, and that the values agree with what
  `MotionSource` logs at the same timestamps.

## What this unlocks

The three-day diagnostic becomes queries like:

```sql
-- What does AR actually report while stationary?
SELECT motion_activity, motion_confidence, count(*)
FROM samples WHERE ts_ms > … GROUP BY 1,2 ORDER BY 3 DESC;

-- Does the gate's verdict track the readings, or is the debounce wrong?
SELECT motion_activity, motion_confidence, motion_still, count(*)
FROM samples WHERE ts_ms > … GROUP BY 1,2,3;

-- Was GPS on during transit, and what did the phone think it was doing?
SELECT to_timestamp(ts_ms/1000), lat IS NOT NULL AS has_gps, motion_activity, motion_still, current_a
FROM samples WHERE ts_ms BETWEEN … AND … ORDER BY ts_ms;
```

That last one is precisely the question that cost three days.
