# Silence-as-Stillness Motion Gate — Design

**Date:** 2026-08-09
**Status:** Approved by user (risk posture, hold length, and design approved in conversation).
**Supersedes:** the debounce/staleness close semantics of `2026-08-06-motion-gated-gps-design.md`.
The reopen semantics, `gpsShouldRun`, and all plumbing outside the fold survive unchanged.

## Goal

Make the parked-GPS pause actually fire. The shipped gate closes only on 3 distinct fresh
confident-STILL readings inside a 150 s freshness window — evidence that, in the true parked
regime, never arrives.

## Why: the field finding that forced this

Measured 2026-08-09, the first morning `motion_at_ms` existed (prod SQL + logcat, memory note
`bmsmon-ar-delivery-motion-gated`):

- **The gate never closed once overnight.** 70,779 rows from 19:55 → 12:44, `motion_still` false
  on all, zero flips. GPS ran all night (47.4% of rows carry indoor fixes).
- **Play Services delivered 4 readings in ~18 hours.** Healthy ~34 s cadence 18:15–18:22 CDT
  (people around the chair), then silence; one reading at each app restart; one spontaneous
  reading at 06:45. All STILL@94–100.
- **AR delivery is motion-triggered at the sensor level.** Rich in vehicles (859 IN_VEHICLE at
  ~5.7 s cadence on the 08-08 outing), essentially dead when the device is genuinely still. The
  earlier "5.7 s while stationary" traces were taken with a human actively handling the device.

Consequence: treating a silent signal as "no evidence → fail open" (`MOTION_STALE_MS`) guarantees
the gate reopens and stays open exactly when the phone is most parked. **Under the
motion-triggered delivery model, silence after a confident STILL is itself stillness evidence** —
AR goes quiet *because* nothing is moving, and wakes *because* something is. The design inverts
the staleness semantics accordingly.

## Fold semantics

`MotionGate` (`model/BatterySaver.kt`, stays pure — no clock access, `nowMs` threaded in):

```kotlin
data class MotionGate(
    val stillSinceMs: Long? = null,   // start of the current uncontradicted confident-STILL run
    val still: Boolean = false,       // the verdict: closed once the run is HOLD old
    val lastConfidentAtMs: Long = 0L, // dedup key, unchanged role
)
```

`foldMotion(prev, reading, nowMs)` branch order (order is load-bearing, as before):

1. **`reading == null`** → `MotionGate()`. Fail open: no signal ever, or the source was stopped
   (stop clears the cache). Every unusable-signal path still lands here.
2. **`reading.atMs == prev.lastConfidentAtMs`** (already folded) → hold the run, **re-derive the
   verdict from the clock**: `still = stillSinceMs != null && nowMs - stillSinceMs >= STILL_CLOSE_HOLD_MS`.
   This branch is how silence closes the gate — evaluations keep arriving (per BLE frame, plus
   the 5-min range tick) while readings do not. Note the change from the old design, whose dedup
   branch returned `prev` untouched.
3. **`confidence < STILL_CONFIDENCE_MIN`** → same as branch 2: uncertainty neither starts, breaks,
   nor ends a run, and there is no longer a staleness deadline for it to postpone.
4. **Confident STILL** → start the run if none (`stillSinceMs = reading.atMs` — the evidence's own
   time, not fold time), else keep the existing start; `lastConfidentAtMs = reading.atMs`; verdict
   from the clock as in branch 2.
5. **Confident non-STILL** → `MotionGate(lastConfidentAtMs = reading.atMs)`. Reopens on a single
   reading, unchanged.

**Deleted: `MOTION_STALE_MS` and `STILL_DEBOUNCE_N`**, and the staleness branch with them. The
debounce's anti-flap job moves to the hold: a spurious stoplight STILL must survive **10
uncontradicted minutes** to close the gate, and in a real drive AR's rich in-vehicle delivery
contradicts it long before that. Replayed against the recorded traces: the 08-07
STILL@96–100/UNKNOWN@41–50 interleave produces zero flaps (UNKNOWNs hold, STILLs extend one run,
close at +10 min, nothing reopens); the overnight regime closes 10 min after the first
subscribe-burst reading and stays closed until morning motion; a ~5-min train-station stop never
closes (10-min hold, user-chosen over 5 precisely for this case), and a longer one self-corrects
on departure at the cost of one GNSS restart.

**Kept:** `STILL_CONFIDENCE_MIN = 75`, the dedup-by-`atMs` mechanism, `shutdownGps()`'s gate
reset, and `gpsShouldRun` verbatim — pausing still requires the discharge half (`gpsParked`) AND
the gate, so any discharge resumes GPS regardless of the gate's verdict.

**New constant:** `STILL_CLOSE_HOLD_MS = 10 * 60_000L` — deliberately a separate constant from
`PARKED_HOLD_MS` (5 min): the discharge hold defines "chair parked", this defines "phone still
long enough that a mid-trip standstill is implausible". User chose 10 min over 5.

## Backstop: periodic re-subscribe

The inverted semantics mean a silently-dead AR subscription can now hold the gate *closed* while
parked (before: it could only cost saving). Reopen paths that survive a dead subscription:
discharge (chair outings always discharge at the start — loading/driving), and this backstop:

- `MotionSource` tracks `lastRequestAtMs` and gains `resubscribe()` (`@Synchronized`, no-op unless
  `requesting`): re-issues `client.requestActivityUpdates(INTERVAL_MS, pendingIntent())` on the
  same `FLAG_UPDATE_CURRENT` PendingIntent — a refresh, not a re-registration; the receiver is
  untouched; failures route through the existing `onSubscribeFailed` rollback.
- `MonitorEngine`'s existing 5-min range-loop tick calls it when `RESUBSCRIBE_MS = 6 * 3_600_000L`
  (6 h) has elapsed since the last request, only while the source should be running (same
  `gpsWanted && gpsPauseParked` condition that drives start/stop).

Observed bonus that makes this an active probe, not just hygiene: both post-restart subscriptions
delivered one immediate reading (19:55:45, 12:44:07 bursts). If refreshes do the same, each one
either confirms the run (extends nothing — the run is already unbroken) or reveals motion and
reopens the gate. **Expected but unverified for the refresh path — confirm on-device after
deploy; the backstop's correctness does not depend on it.**

## Self-healing restarts

Today a restart resets the in-memory gate and it never re-closes while parked (the recorded
telemetry era shows zero closes; the one known close, on the 08-08 outing's arrival, happened
amid arrival bustle that was still generating readings). Under this design the restart's own subscribe-burst
reading starts a run and the gate closes 10 minutes later — an `install -r` or crash while parked
costs 10 minutes of GNSS, not the whole night.

## Failure directions

- No permission / AR unavailable / subscribe failure / no reading yet / source stopped → branch 1,
  **fail open, GPS on** — identical to today.
- Dead subscription while open → gate stays open, GPS on — identical to today.
- Dead subscription while closed → bounded by discharge reopen + 6 h refresh; accepted by explicit
  user decision (risk-posture question, "cheap backstop" option).
- All packs out of BLE range → evaluations still arrive via the 5-min range tick; worst case the
  close lags the hold boundary by one tick, which errs toward GPS-on.

## Observability

No new telemetry. `motion_at_ms` (reading arrivals), `motion_activity`/`motion_confidence`
(evidence), and `motion_still` (verdict) reconstruct this gate fully in SQL — a closed verdict
requires the last confident reading STILL and ≥10 min elapsed, both checkable per row. Today's
diagnosis is the working proof of that reconstruction.

## Testing

`BatterySaverTest` motion tests rewritten to the new rules (JVM, pure — same harness as today):
run starts on confident STILL; silence closes at exactly the hold boundary (inclusive/exclusive
pinned by test); silence before the boundary does not close; uncertainty holds an open run, a
closed gate, and a not-yet-started state; a single confident non-STILL reopens from any state;
dedup branch re-derives the verdict (same reading folded twice, second fold past the boundary,
closes); restart self-heal (fresh gate + one reading + time = closed); `reading == null` fails
open from closed. `MotionSource.resubscribe()` has no JVM harness (Play Services) — on-device
verification, like `start()`/`stop()` today.

On-device after deploy: `motion_still = true` rows appear in prod within ~10 min of a parked
restart; a refresh at the 6 h mark logs a new reading (or the absence is recorded as a finding,
per the note above).

## Out of scope

- **Server/WebUI** — nothing changes on the wire or in storage.
- **The settings "Motion sensing active" line** — 5.3 minor, still parked.
- **Sleep API / significant-motion sensor** — new surface without measurement; rejected.
- **`INTERVAL_MS` tuning** — the 30 s request demonstrably does not control delivery; leave it.
- **AR power cost** — separate open item (3.2); note the pause firing again ends the "clean
  natural experiment" window that began 2026-08-08.

## Documentation

`CLAUDE.md`'s motion-gate sections describe the debounce/staleness semantics at length — the
rework must update them (fold rules, deleted constants, the field finding, the backstop), and the
`bmsmon-ar-delivery-motion-gated` memory gets a "fix shipped" line when deployed.
