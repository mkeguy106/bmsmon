# Editable Battery & Group Roster — Design

**Date:** 2026-06-27
**Component:** Android app (`android/`)
**Status:** Approved (design); ready for implementation plan

## Problem

The Android app's batteries and groups are compile-time constants in
`model/BatteryGroup.kt`:

- `ALL_GROUPS: List<BatteryGroup>` — a fixed list of 4 bases.
- A `BatteryGroup` is rigidly **exactly two** batteries (`a` and `b`).
- This constant is read pervasively: monitoring targets, stage resolution
  (`resolveStage` / `StageInputs`), `groupById`, `groupForAddress`, `demoFor`,
  `stageItems`, regen detection, the All Batteries filter chips, etc.

The user wants batteries and groups to become **user-editable data**: add new
batteries via BLE scan, remove them, rename them, move them between groups,
create groups, and rename groups — plus a per-battery detail page.

This is an architectural shift from a hardcoded constant to **persisted,
mutable, reactive state**.

## Hard constraints

1. **Do not lose current names or groupings.** The app ships with the user's
   real deployed setup: **4 groups of 2**, with these exact group labels,
   battery aliases, and addresses (the source of truth is the current
   `ALL_GROUPS`):

   | Group | Battery A (alias / MAC) | Battery B (alias / MAC) |
   |-------|--------------------------|--------------------------|
   | `2012` | `2012 · A` / `C8:47:80:15:67:44` | `2012 · B` / `C8:47:80:15:62:1B` |
   | `2016` | `2016 · A` / `C8:47:80:15:DB:13` | `2016 · B` / `C8:47:80:15:25:9A` |
   | `2023` | `2023 · A` / `C8:47:80:46:0A:D6` | `2023 · B` / `C8:47:80:45:90:FB` |
   | `2024` | `2024 · A` / `C8:47:80:15:07:DE` | `2024 · B` / `C8:47:80:15:25:01` |

   Daily driver = `2012`.

2. **Testing must be reverted.** Add/remove/group operations may be exercised
   on the phone during development, but the persisted roster must be restored
   to exactly the seed above when done.

3. **Safety unchanged.** Scanning surfaces and connects to **compatible BMS
   devices only** (the existing `KNOWN_PREFIXES` filter). Never probe or
   connect to unknown BLE hardware. No new command bytes are ever sent — this
   feature is read-only telemetry plus local roster edits.

## Decisions (from brainstorming)

- **Group size:** any size `0..N`. Ungrouped batteries are loose/standalone.
- **Seed:** on first launch with no persisted roster, populate from the current
  `ALL_GROUPS` exactly (table above).
- **Add flow:** tapping a discovered battery **quick-adds** it immediately as an
  ungrouped pack, alias defaulted to its BLE advertised name. Edit/group later.
- **Row gestures:** **single tap = detail page** (replaces the current no-op);
  **long-press = action menu**; **swipe-left = delete**. "Pin to stage" moves
  from double-tap into the long-press menu. Triple-tap is dropped.
- **Scan filter:** compatible BMS prefixes only.

## Architecture

### 1. Data model

Replace the fixed `BatteryGroup(a, b)` structure with a flat roster:

```kotlin
/** One battery in the roster. `address` is the immutable identity / dedup key. */
data class Battery(
    val address: String,        // MAC, normalized uppercase
    val advertisedName: String, // "real name" captured from BLE scan; immutable
    val alias: String,          // editable display name
    val groupId: String?,       // null = ungrouped
)

/** A user-defined group; holds 0..N batteries. */
data class Group(
    val id: String,             // stable key
    val name: String,           // editable label
)

data class Roster(
    val batteries: List<Battery>,
    val groups: List<Group>,
)
```

The legacy `BatteryGroup` / `BmsTarget` types become **derived views** computed
from the roster, so existing consumers (`groupById`, `groupForAddress`,
`demoFor`, `.targets`, stage logic) keep working with minimal churn. They read
from the live roster instead of a constant. `BatteryGroup` loses its hard `a`/`b`
fields in favor of a `targets: List<BmsTarget>` derived from group membership.

The canonical seed roster (the table above) lives in one place
(`DEFAULT_ROSTER` in the model) and is the single source of truth for both
first-run seeding and the restore guarantee.

### 2. Persistence

Add a `Roster` JSON blob to `SettingsStore` (DataStore), following the existing
`lastTelemetry` JSON pattern (`org.json`, NaN-safe, defensive decode). On load:
if absent or undecodable, fall back to `DEFAULT_ROSTER`. New
`SettingsStore.setRoster(roster)` / roster field on `Persisted`.

### 3. Reactive state

- `Roster` moves into `UiState` (`roster: Roster`) and is loaded in the
  ViewModel `init` alongside other prefs.
- Everything that reads `ALL_GROUPS` switches to `state.roster`:
  - `startMonitoring` targets = all roster batteries.
  - `resolveStage` / `refresh` iterate roster groups.
  - `stageItems`, `stageLabel`, `stageRegen`, filter chips, `demoFor`.
- ViewModel gains roster mutations, each persisting and re-resolving:
  - `addBattery(address, advertisedName)`
  - `removeBattery(address)`
  - `renameBattery(address, alias)`
  - `setBatteryGroup(address, groupId?)`
  - `createGroup(name) -> id` and assign
  - `renameGroup(groupId, name)`
- **Edge handling on removal/regroup:** if the removed/moved battery is on the
  stage or is the daily driver, fall back gracefully (re-resolve stage; if the
  daily-driver group disappears, pick another or clear). When monitoring, the
  repository's target set is rebuilt so dropped batteries stop being polled and
  added ones start.

### 4. Add batteries (BLE scan)

- **Entry points:**
  - Empty roster → main stage shows a large centered **+** ("Add a battery").
  - Non-empty roster → **+** icon in the All Batteries header (top-right).
- Both open a **scan sheet** (modal bottom sheet).
- New `BleScanner` performs a fresh BLE scan, filtered to compatible prefixes.
  Captures each device's address + advertised name.
- Each result compared by MAC to `state.roster.batteries`:
  - **Known** → dimmed, label "Added as `<alias>`", not tappable.
  - **New** → tappable; tap → `addBattery(...)` (quick-add, ungrouped, alias =
    advertised name). Row stays in the sheet, now showing as added/dimmed, so
    several can be added in one pass.
- Requires BLE scan permission (reuse `BlePermissions`); prompt if missing.

### 5. Row actions: remove / rename / regroup

**Long-press → dropdown menu** (extends the existing `RowActionMenu`):

- Pin to Main Stage (moved from double-tap)
- Rename battery → text dialog (pre-filled with current alias)
- Add to group / Move to group → submenu of existing groups + "New group…"
  (name dialog → `createGroup` + assign)
- Remove from group (if grouped)
- Rename group (only shown if the battery is in a group) → text dialog
- Remove battery → **confirmation dialog** ("Remove this battery? Are you
  sure?") → `removeBattery`

**Swipe-left** on a row → reveals a **trash icon** as the row slides; completing
the swipe triggers the **same confirmation dialog** → `removeBattery`.
Implementation: Material3 `SwipeToDismissBox` (left/`EndToStart` only), with the
dismiss gated behind the confirmation (snap back if cancelled).

### 6. Detail page (single tap)

Single tap a row → navigate to a **Battery Detail** screen. Add a `Detail`
screen value (or a nullable `detailAddress` in `UiState`) and route from
`App.kt`. Shows everything safely gathered:

- **Identity:** alias, real (advertised) name, MAC, current group.
- **Per-cell status:** `telemetry.cells: List<Float>` — list each cell's
  voltage; highlight min / max / delta.
- **All safe telemetry:** SOC, SOH, cycles, voltage, current, power, remaining
  capacity, full-charge capacity, cell temp, MOSFET temp, state, active
  protection flags. (Respect the existing °F/°C setting for temperatures.)
- **Future graphs:** a clearly-labeled placeholder section ("History — coming
  soon") for charts from logged data. Not wired in this change.

Reads from `state.fleet[address]` (live or last-known); shows a sensible empty
state when no telemetry yet.

### 7. Stage with 3+ packs

Groups can now exceed two batteries. The main-stage pack row becomes
**horizontally scrollable** when the staged group has 3+ packs; the existing
dual-ring gauge layout is unchanged for 1–2 packs (the common real case).

## Components & boundaries

| Unit | Responsibility | Depends on |
|------|----------------|------------|
| `model/Roster.kt` (new) | `Battery`, `Group`, `Roster`, `DEFAULT_ROSTER`, derived `BatteryGroup`/target views, helpers | — |
| `model/BatteryGroup.kt` | becomes derived views over roster; stage logic reads roster | Roster |
| `data/SettingsStore.kt` | persist/load `Roster` JSON | Roster |
| `ble/BleScanner.kt` (new) | compatible-only scan → discovered devices | BlePermissions |
| `BatteryViewModel.kt` | roster in `UiState`; mutations; re-resolve; rebuild targets | Roster, SettingsStore, BmsRepository |
| `ui/all/AllBatteriesScreen.kt` | header **+**, long-press menu, swipe-delete, single-tap → detail | ViewModel |
| `ui/scan/ScanSheet.kt` (new) | scan UI, known-dimmed, quick-add | ViewModel, BleScanner |
| `ui/detail/BatteryDetailScreen.kt` (new) | per-battery detail + cells + graph placeholder | ViewModel |
| `ui/home/StageScreen.kt` | horizontal scroll for 3+ packs; empty-roster **+** | ViewModel |
| `App.kt` | route Detail screen + scan sheet | — |

## Testing

- **Unit:** assert `DEFAULT_ROSTER` exactly matches the current hardcoded
  values (4 groups of 2, exact labels/aliases/MACs, daily driver `2012`) — a
  drift guard for constraint #1.
- **Unit:** roster mutations (add dedups by MAC; remove; rename; regroup;
  create group; rename group) produce expected rosters.
- **Unit:** stage re-resolution when the staged/daily-driver battery is
  removed or moved.
- **Unit:** `SettingsStore` roster round-trips (encode → decode), and a missing
  blob falls back to `DEFAULT_ROSTER`.
- **Manual (then revert):** scan & add a battery, rename, group/regroup, rename
  group, remove (menu + swipe, with confirm), open detail page. **Restore the
  roster to the seed afterward.**

## Out of scope (this change)

- History graphs on the detail page (placeholder only).
- Power-ring calibration (tracked separately).
- Any new/destructive BLE commands.

## Risks

- **Pervasive `ALL_GROUPS` usage.** Mechanical but wide; the derived-view
  approach contains the blast radius. Compile + existing tests catch misses.
- **Gesture coexistence.** Swipe-left vs long-press vs single-tap on one row —
  verify on-device that swipe-to-delete doesn't fight the tap/long-press.
- **Stage/daily-driver pointing at a removed battery** — must re-resolve, not
  crash.
