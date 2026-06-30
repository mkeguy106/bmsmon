# Stage Alert Notifications — Design

**Date:** 2026-06-30
**Status:** Approved

## Problem

Stage low-SOC alerts never reach the user when they need them. Three distinct gaps,
discovered when a stage pack hit the user's configured "critical" level (75%) while the app
was locked and backgrounded, and no alert was seen:

1. **Misleading boundary.** The trigger uses strict `<` (`stageAlert()`,
   `BatteryViewModel.kt`), so a threshold labeled "75%" only fires at 74% and below. The
   user reasonably expected "75%" to fire *at* 75%. The Critical-level help text even says
   "at **or below** this level", contradicting the actual `<`.

2. **Config trap.** "Critical level" is only a *severity classifier* — it decides whether an
   already-triggered alert renders red/critical. It does **not** arm a trigger. Triggers come
   from the separately-toggled **Thresholds** chips. Setting only the Critical level (without
   enabling the matching threshold) silently does nothing.

3. **No headless delivery.** Alert evaluation lives only in `BatteryViewModel.stageAlert()`,
   which exists only while the UI is alive, and the only output is the in-app `DangerOverlay`
   flash (rendered only with the stage foregrounded, screen on). There is no notification,
   sound, or vibration — so an alert is invisible when the phone is idle/backgrounded.

## Goals

- A threshold of N% fires **at** N% (`<=`).
- Alerts fire **headless** as phone notifications, surviving backgrounding / screen-off.
- **All armed thresholds** notify: critical ones loud (sound + vibration, heads-up),
  warnings quiet/silent.
- Picking a Critical level **auto-arms** the matching threshold so it can't silently fail.

## Non-goals (YAGNI)

- Full-screen alarm / `USE_FULL_SCREEN_INTENT`.
- Per-pack (vs per-stage) alerts.
- Alert history / log.

## Architecture

### 1. Shared pure alert logic — `model/Alerts.kt` (new)

Single source of truth used by both the UI and the engine.

```kotlin
data class AlertConfig(
    val alertsOn: Boolean,
    val enabledThresholds: Set<Int>,
    val criticalThreshold: Int,
)

data class AlertEval(
    val activeThreshold: Int?,   // lowest enabled threshold the stage has dropped to/below; null = no alert
    val critical: Boolean,       // activeThreshold != null && activeThreshold <= criticalThreshold
    val lowSoc: Int,             // rounded SOC of the lowest reachable stage pack
    val charging: Boolean,       // lowest pack is charging (suppresses)
)

/** Pure. [stageSocs] are the reachable stage packs' (soc, charging) pairs. */
fun evalStageAlert(stageSocs: List<PackSoc>, cfg: AlertConfig): AlertEval
```

- Boundary fix: `crossed = enabledThresholds.filter { lowSoc <= it }` (was `<`).
- Lowest reachable pack wins; empty → no alert.
- `alertsOn == false` → no alert.
- Charging lowest pack → `charging = true`; callers suppress.

`BatteryViewModel.stageAlert()` keeps its acknowledgement layer (UI-only) but delegates the
threshold/critical math to `evalStageAlert`. In-app flash behavior is otherwise unchanged.

### 2. Headless evaluation — `MonitorEngine`

- New `setAlertConfig(cfg: AlertConfig)`, pushed by the ViewModel on monitoring start and
  whenever `alertsOn` / `enabledThresholds` / `criticalThreshold` change (mirrors
  `setStage`/`setDisabled`).
- Engine retains the stage addresses (today it only forwards `setStage` to BLE). On each
  fleet/state update it computes `evalStageAlert` over the reachable stage packs and drives
  the notifier.

### 3. Notifications — `monitor/AlertNotifier.kt` (new)

- Two channels, created once:
  - **Critical** — `IMPORTANCE_HIGH`, sound + vibration, heads-up.
  - **Warning** — `IMPORTANCE_LOW`, silent.
- Distinct notification ID from the foreground-service ongoing notification.
- **Dedup** as a pure function for testability:

```kotlin
data class NotifyDecision(val notify: Boolean, val cancel: Boolean, val newLastNotified: Int?)
fun nextNotifyDecision(eval: AlertEval, lastNotified: Int?): NotifyDecision
```

Rules:
- No alert (`activeThreshold == null`) or `charging` → `cancel = true`, `newLastNotified = null`.
- Else `notify = (lastNotified == null || activeThreshold < lastNotified)` — i.e. first
  crossing or an escalation to a more-severe (lower) band.
- Always `newLastNotified = activeThreshold` so a recovery to a less-severe band resets the
  baseline and a subsequent re-drop re-notifies. Sitting in the same band → no repeat.

The Android `Notification` construction (channel pick, text, vibration pattern) is a thin
wrapper around the decision and is not unit-tested (framework).

### 4. UI — `SettingsScreen` + `BatteryViewModel`

- `setCriticalThreshold(t)` also adds `t` to `enabledThresholds` (and persists both) — auto-arm.
- Clearer copy: Thresholds = "Alert when a stage pack drops to these levels."; Critical level =
  "At or below this level, alerts are loud (sound + vibration)."

### 5. Manifest

- Add `android.permission.VIBRATE` (normal, auto-granted). `POST_NOTIFICATIONS` is already
  requested opportunistically.

## Testing

- `evalStageAlert` (unit): boundary at exactly 75 with threshold 75 (`<=`); critical
  classification (`activeThreshold <= criticalThreshold`); charging suppression; disconnected
  packs excluded; lowest-pack selection; `alertsOn == false`.
- `nextNotifyDecision` (unit): first-fire; escalation; no-spam within a band; cancel on
  recovery; cancel on charging; re-notify after recovery-then-re-drop.
- `BatteryViewModel`: `setCriticalThreshold` auto-arms the matching threshold.
- Notification building: thin, untested.

## Files

- `model/Alerts.kt` (new) — `AlertConfig`, `AlertEval`, `evalStageAlert`, `nextNotifyDecision`.
- `BatteryViewModel.kt` — delegate to `evalStageAlert`; `setCriticalThreshold` auto-arm; push
  `setAlertConfig` to engine.
- `monitor/MonitorEngine.kt` — retain stage addrs + alert config; evaluate on update; notify.
- `monitor/AlertNotifier.kt` (new) — channels, notify/cancel, holds `lastNotified`.
- `ui/settings/SettingsScreen.kt` — clearer copy.
- `AndroidManifest.xml` — `VIBRATE`.
- tests: `AlertsTest.kt`, ViewModel auto-arm test.
