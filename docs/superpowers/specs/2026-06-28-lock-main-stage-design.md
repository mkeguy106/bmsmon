# Lock Main Stage (Kiosk Lock) — Design

**Date:** 2026-06-28
**Project:** bmsmon Android app (`android/`)
**Status:** Approved design, pending implementation plan

## Problem

The app runs as a wheelchair-mounted battery dashboard. Accidental swipes, bumps, or
stray touches can navigate away from the main stage or out of the app entirely. We want a
way to aggressively keep the app pinned to the main stage until it is deliberately
unlocked from within the app — not the phone's screen lock, but an app-level kiosk lock.

## Goals

- A lock control on the main stage that, when engaged, keeps the app up and frozen on the
  stage regardless of gestures/swipes.
- Works immediately on an ordinary install, and can be upgraded to a truly inescapable
  kiosk on a dedicated phone without a code change or rebuild.
- Resist accidental locking *and* accidental unlocking (wheelchair-mount context).

## Non-Goals

- Auto-relaunch on reboot (would require device-owner HOME-activity provisioning). Noted as
  a possible future step; not built here.
- Replacing or altering the phone's own lock screen / security.
- Any change to the BLE protocol, monitoring engine, or foreground service behavior.

## Decisions

| Decision | Choice |
|----------|--------|
| Lock strength | **Device-owner-aware** (Option C): screen pinning by default; true kiosk if provisioned as device owner |
| In-app navigation while locked | **Freeze on the stage** — disable pager swipe, hide Settings/appearance/monitoring controls |
| Lock & unlock interaction | **Symmetric long-press ~1.5 s** with a filling progress ring; release early = no-op (confirms both lock and unlock) |
| Screen while locked | **Force screen on** for the duration; revert to the Settings value on unlock |
| Auto light/dark | **Still honored** while locked (System + Auto/ambient-light keep adapting); only the manual appearance cycle button is hidden |
| Button location | **Top bar, right side** |

## Android Mechanism: Lock Task Mode

Android's only sanctioned way to trap the user in an app is **Lock Task Mode**.

- **Ordinary app:** `startLockTask()` enters *screen pinning*. Home, Recents, and
  swipe-away are blocked, but the OS guarantees an escape via the Back+Recents
  hold gesture. Cannot be removed for a non-privileged app.
- **Device owner app:** after `setLockTaskPackages(admin, [pkg])`, `startLockTask()`
  enters a true kiosk — the escape gesture is gone; only an in-app `stopLockTask()`
  exits.

Device-owner-aware behavior: at runtime check `DevicePolicyManager.isDeviceOwnerApp(pkg)`.
If true, allowlist the package and enter the strong mode; otherwise fall back to pinning.
One code path; provisioning later upgrades the strength with no rebuild:

```
adb shell dpm set-device-owner dev.joely.bmsmon/.BmsDeviceAdminReceiver
```

(Requires a phone with no other accounts; device owner is removable only by factory reset.)

## Architecture & Components

The lock is **Activity-level UI state**, independent of the `MonitorEngine` /
`MonitoringService` (monitoring is untouched and coexists with the lock).

1. **State**
   - Add `locked: Boolean` to `UiState`.
   - Persist in `SettingsStore` so a process kill re-enters lock on next launch.
   - `BatteryViewModel.setLocked(Boolean)` updates and persists it.

2. **Lock Task control (`ui/App.kt`)**
   - Mirror the existing keep-screen-on pattern. A `LaunchedEffect(state.locked)`
     resolves the hosting Activity (`context.findActivity()`) and calls
     `startLockTask()` / `stopLockTask()`.
   - If `DevicePolicyManager.isDeviceOwnerApp(packageName)`, call
     `setLockTaskPackages(admin, arrayOf(packageName))` before `startLockTask()` so it
     enters the escape-gesture-free kiosk mode.
   - The keep-screen-on `DisposableEffect` keys on `state.keepScreenOn || state.locked`
     so locking forces the screen on and unlocking reverts to the setting.

3. **Top bar (`ui/home/HomeScreen.kt` → `TopBar`)**
   - Add a `LockButton` composable: a lock/unlock icon with a press-and-hold gesture that
     animates a progress ring filling over ~1.5 s; fires the toggle on completion and
     resets if released early.
   - When `locked`: hide the appearance icon, the Settings icon, and the monitoring
     toggle; append `· LOCKED` to the status label; render only the lock icon
     (long-press to unlock).

4. **Pager freeze (`ui/home/HomeScreen.kt`)**
   - `HorizontalPager(userScrollEnabled = !locked)`.
   - If `locked` and `currentPage != 0`, snap to page 0.
   - Hide `PageDots` while locked.

5. **Device-owner plumbing (manifest + res)**
   - Minimal `BmsDeviceAdminReceiver` (extends `DeviceAdminReceiver`).
   - `res/xml/device_admin.xml` policy file.
   - Manifest `<receiver>` registration so the `dpm set-device-owner` command resolves the
     component. No runtime effect until that command is run.

## Data Flow

```
long-press LockButton (1.5s)
   → vm.setLocked(true)               (UiState.locked = true, persisted)
   → App.kt LaunchedEffect(locked)
        → [device owner?] setLockTaskPackages(...)
        → activity.startLockTask()
   → keep-screen-on effect sees locked → FLAG_KEEP_SCREEN_ON on
   → HomeScreen: pager frozen to page 0, controls hidden, "· LOCKED" shown

long-press LockButton again (1.5s)
   → vm.setLocked(false)
   → App.kt: activity.stopLockTask()
   → screen-on reverts to settings value; controls restored
```

## Edge Cases

- First pinning on a non-device-owner phone triggers Android's one-time "Screen pinned"
  system toast/confirmation — expected, not an error.
- The danger/alert overlay and its Acknowledge button remain functional while locked
  (in-app interaction).
- Foreground service and BLE monitoring are unaffected by lock state.
- If `startLockTask()` throws or is unavailable (e.g. another lock-task app active), the
  app stays usable; `locked` UI freeze still applies as a soft fallback.
- **`Screen.Detail` and the `ScanSheet` (added by the editable-roster work) are unreachable
  while locked:** the detail screen is only opened from the All Batteries page (which the
  pager freeze blocks), and the scan sheet is only triggered by an "add" affordance shown on
  the All Batteries page and on the *empty-roster* stage placeholder. Freezing on page 0 with
  a populated roster therefore exposes neither. Known minor edge: locking with an **empty
  roster** still shows the stage's "add" button (it sits on page 0); this is harmless and
  nonsensical to do in practice, so it is left as-is rather than special-cased.

## Testing

- **Unit:** lock-state reducer — `setLocked(true/false)` updates `UiState.locked`;
  `SettingsStore` persistence round-trips the flag. Follow the `FleetLogicTest` /
  `AppearanceTest` style.
- **Manual:**
  - Long-press lock → swipe to All Batteries blocked, Home/Recents/swipe-away blocked,
    screen stays on, theme still auto-switches (System/Auto).
  - Long-press unlock → navigation, controls, and screen-on setting restored.
  - Device-owner path: run the `dpm set-device-owner` command on a spare/dedicated phone,
    confirm the escape gesture is gone and only the in-app unlock exits.
