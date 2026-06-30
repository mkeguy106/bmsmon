# Cloud-Upload Indicator on the Main Stage

**Date:** 2026-06-30
**Status:** Approved-by-delegation (user asked for this while away; build but do NOT install)
**Scope:** Android app (`android/`) — Home main-stage top bar + cloud reporter status.

## Motivation

The user wants a small, glanceable indicator on the Home "main stage" confirming that cloud
upload is working, preferably showing a KB/s-style throughput. Not big — just enough to glance
at and see uploads are flowing.

## Decisions

- Show a compact token in the existing top-bar **left row**, right after the stage status label
  (e.g. `2012 · IDLE  ↑ 0.4 KB/s`). Visible only when `cloudEnabled && enrolled`.
- Throughput = a **5-second rolling window** of actual POST body bytes → smoothed KB/s. During
  active monitoring (small batches every ~1.5 s) the window stays populated, so the rate reads a
  steady small value rather than flickering to 0.
- Display states (priority): **active** (`rate > 0.05`): `↑ 0.4 KB/s` green; **buffering**
  (`rate ≈ 0 && depth > 0`): `↑ N queued` amber; **synced** (`rate ≈ 0 && depth == 0 && everUploaded`):
  `↑ synced` dim; **idle** (enrolled, nothing yet): `↑ idle` dim.
- No persistence, no settings, no new permissions. Pure read-only status.

## Components

### 1. `cloud/UploadRate.kt` (new, pure, unit-tested)
Rolling-window byte-rate. Single-threaded use (the reporter's upload loop).
- `class UploadRate(windowMs: Long = 5000)`
- `record(nowMs: Long, bytes: Int)` — append `(now, bytes)`, prune old.
- `kbps(nowMs: Long): Double` — prune, `sumBytes * 1000.0 / windowMs / 1024.0`.
- prune drops entries with `now - t > windowMs`.

### 2. `cloud/TelemetryReporter.kt`
- Hold `private val uploadRate = UploadRate()`.
- Extend the callback: `onStatus: ((outboxCount: Long, lastUploadMs: Long, kbps: Double) -> Unit)?`.
- Idle/offline branch (currently line 192): pass `uploadRate.kbps(System.currentTimeMillis())`
  (decays toward 0 as the window empties).
- Success branch (currently 205-208): after `deleteUpTo`, `uploadRate.record(now, body.size)`
  then `onStatus?.invoke(depth, lastUploadMs, uploadRate.kbps(now))` using the same `now`.

### 3. `BatteryViewModel.kt`
- `UiState`: add `val cloudUploadKbps: Float = 0f` (next to `cloudOutboxDepth`/`cloudLastUploadMs`).
- `onStatus` lambda (currently lines 325-327): also set `cloudUploadKbps = kbps.toFloat()`.

### 4. `ui/home/HomeScreen.kt`
- In `TopBar`, inside the left `Row` after the status-label `Text` (line 246), render a small
  `UploadBadge(state)` `Text` (~9.5 sp, `letterSpacing 0.6.sp`, `padding(start = 10.dp)`),
  shown only when `state.cloudEnabled && state.enrolled`.
- Text + color per the state table above, using existing tokens: `RegenGreen` (active),
  `AlertWarn` (buffering), `c.text3` (synced/idle).
- Format active rate as `"↑ %.1f KB/s".format(kbps)`.

## Data flow

upload success → `uploadRate.record(now, body.size)` → `onStatus(depth, ts, kbps)` →
`UiState.cloudUploadKbps` → `TopBar` badge. Idle ticks (~1.5 s) re-report a decaying kbps.

## Edge cases

- Not enrolled / cloud off → badge hidden (no clutter).
- Window empties (no recent upload) → `kbps ≈ 0` → badge shows `synced` (caught up) or
  `N queued` (backlog/offline), distinguishing "working, nothing to send" from "buffering".
- Single-threaded reporter loop owns `UploadRate`; no synchronization needed.
- Layout: badge text is short (≤ ~11 chars); the centered page dots are absolutely positioned, so
  the growing left row does not move them.

## Testing

- `UploadRateTest` (JVM unit): empty→0, sum-in-window, prune-old, partial-window. Deterministic
  via injected `nowMs`.
- Build `:app:assembleDebug`. **No device install** (per user instruction). Visual verification
  deferred until the user approves installing.

## Files touched

`cloud/UploadRate.kt` (new) + `cloud/UploadRateTest.kt` (new),
`cloud/TelemetryReporter.kt`, `BatteryViewModel.kt`, `ui/home/HomeScreen.kt`.
