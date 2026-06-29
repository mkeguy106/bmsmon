# Battery Profiles & Persistent-Fleet Connection Manager — Design

**Date:** 2026-06-29
**Project:** bmsmon Android app (`android/`)
**Status:** Approved design, pending implementation plan

## Problem

Two related problems, settled by the BLE investigation (`docs/ble-connectivity-investigation.md`):

1. **Brand/firmware-specific constants are scattered.** BLE UUIDs live in `BmsProtocol`, name
   prefixes in `BleScanner`, the command frame + response field offsets in `BmsProtocol`, and poll
   cadences in `Fleet.kt`. There is no single place that says "this is how a Redodo/Beken pack
   speaks," so supporting another brand/firmware would mean editing many files and risking the
   safety-critical command set.

2. **Off-stage polling churns the BLE link.** The current `BmsRepository` rotating sampler does
   **connect → read → disconnect** every ~3 s for every non-stage pack, retrying failures forever
   with no backoff. That connect/abort churn is exactly what stresses the finicky Beken BLE
   module (it likely aggravated the stuck-pack episodes we investigated). The official Redodo app —
   captured byte-for-byte — does the opposite: it **holds all packs connected persistently** and
   polls gently, with **patient retry** on connection failure.

## Goals

- A **`BatteryProfile`** abstraction that captures everything brand/firmware-specific (selection
  prefixes, BLE UUIDs, the command frame + opcodes, the response parse offsets/conversions/bounds,
  poll cadences, write type, connection behavior). The rest of the BLE code becomes generic and
  profile-driven. Future brands/firmware = a new profile entry, no engine changes.
- Replace the rotating sampler with a **persistent-fleet connection manager** that mirrors the
  official app: **hold persistent links** (up to a budget), **poll on a per-tier cadence**, and use
  **exponential backoff** only on the connect-retry edge ("patient retry, then hold"). Minimize
  connect/disconnect churn.
- Preserve the safety guarantee: the app remains strictly read-only; the command set stays a
  closed enum with no destructive opcodes.
- Keep the main stage's behavior (now 1.5 s, validated against Redodo) — it already works great.

## Non-Goals

- No change to telemetry storage, graphs, or UI screens (beyond what the manager exposes, which is
  the same `onPoll`/`onReachable` surface the engine already consumes).
- No dynamic firmware-based profile switching — selection is by BLE name prefix; firmware version is
  recorded as profile metadata only (a future firmware that changes offsets would get a new profile).
- No new brands implemented now — only the one Redodo/Beken profile (the abstraction makes adding
  more trivial later).
- No user-facing "max connections" setting — the budget is a profile constant (default 8).

## Decisions

| Decision | Choice |
|----------|--------|
| Spec scope | **One combined spec, two implementation phases** (profile refactor first, then fleet manager) |
| Profile selection | **By BLE name prefix** (as `isCompatibleBmsName` already does); firmware version = metadata/notes |
| Connection model | **Hold persistent links up to a cap (default 8)**; rotate only true overflow (fleet > cap) |
| Off-stage churn | **Eliminated for held packs** — connect once, hold, slow-poll; no connect→read→disconnect cycling |
| Connect-retry | **Exponential backoff** (base 5 s, ×2, cap 120 s), reset on success; only on the connect edge |
| Poll cadences | **Stage 1500 ms** (matches captured Redodo live rate), off-stage `SLOW_POLL_MS` (10 s today) |
| Write type | **Adopt ATT Write Request** (with response), matching Redodo (we used Write Command) |
| Safety | **Unchanged** — read-only; command set stays a closed enum; no destructive opcodes anywhere |

## Architecture

Two units; the rest of the BLE code becomes generic.

### `BatteryProfile` + `ProfileRegistry` (new — `ble/profile/`)

A `BatteryProfile` is an immutable data object describing one battery family. A `ProfileRegistry`
holds the known profiles and selects one by advertised name.

```kotlin
data class BatteryProfile(
    // identity / selection
    val id: String,                       // "redodo-beken-bk-ble-1.0"
    val displayName: String,              // "Redodo / LiTime / PowerQueen (Beken BK-BLE-1.0)"
    val namePrefixes: List<String>,       // ["R-12","R-24","RO-","L-","LT-","P-","PQ-","S-","SS-"] — drives selection
    val validatedFirmware: String,        // "BMS app FW V1.4 (T12100); BLE module BK-BLE-1.0 6.1.2" (metadata)

    // BLE GATT
    val serviceUuid: UUID,                // 0000ffe0-…
    val notifyUuid: UUID,                 // FFE1
    val writeUuid: UUID,                  // FFE2
    val cccdUuid: UUID,                   // 0x2902
    val writeWithResponse: Boolean,       // true → ATT Write Request (Redodo); false → Write Command

    // protocol — SAFETY: only prebuilt read-command frames are exposed, never an open opcode API
    val statusFrame: ByteArray,           // prebuilt 00 00 04 01 13 55 AA 17 (the 0x13 read)
    val firmwareFrame: ByteArray,         // prebuilt 00 00 04 01 16 55 AA 1A (the 0x16 read)
    val responseHeader: ByteArray,        // 01 93 55 AA (the status realign marker)
    val layout: TelemetryLayout,          // field offsets + conversions + sanity bounds (see below)

    // cadence
    val stagePollMs: Long,                // 1500
    val slowPollMs: Long,                 // 10_000

    // connection behavior
    val maxHeldConnections: Int,          // 8 — hold all, rotate only overflow
    val connectTimeoutMs: Long,
    val failThreshold: Int,               // consecutive connect failures before "out of range" (3)
    val backoff: BackoffSpec,             // base 5_000, factor 2, cap 120_000
)
```

- **SAFETY — the closed command set is preserved.** The profile does **not** expose an open
  `commandFrame(opcode: Int)` API. It exposes only **prebuilt frame `ByteArray`s for the exact reads
  it supports** (`statusFrame`, `firmwareFrame`). The 8-byte frame builder
  (`00 00 04 01 <cmd> 55 AA <checksum>`, checksum = `sum & 0xFF`) stays **private**, used only to
  construct those constants from a closed `ReadCommand` enum at profile-construction time. So there
  remains **no code path that can emit a destructive opcode** — exactly the guarantee `BmsProtocol`
  gives today, just relocated.
- `TelemetryLayout` holds the offset map and conversions so `parseTelemetry` reads them from the
  profile instead of hard-coded literals. The header-realign + sanity-bounds logic stays in the
  parser, parameterized by `responseHeader` and the layout's bounds.
- `ProfileRegistry.profileFor(name: String?): BatteryProfile?` returns the first profile whose
  `namePrefixes` matches (case-insensitive), or null. `isCompatibleBmsName(name)` becomes
  `profileFor(name) != null`.
- The single Redodo/Beken profile is built from the values already in the codebase + the
  investigation (cadences, write type, budget, backoff).

### `FleetConnectionManager` (the `BmsRepository` rework — `ble/`)

Replaces the rotating sampler with a per-pack persistent-connection state machine plus a scheduler.
Keeps the existing outward interface the engine consumes: `start(scope, targets, onPoll, onReachable)`,
`setStage`, `setTargets`, `setDisabled`, `kickAll`, `stop` — so `MonitorEngine` is largely unchanged.

**Per-pack state:**

```
DISCONNECTED ──connect (within budget, via gate)──► CONNECTING ──ok──► CONNECTED ──poll @ tier rate──┐
     ▲                                                  │ fail                │                        │
     └──── backoff (5s → 10s → 20s … cap 120s) ◄─────────┘                     │ link drops             │
     └────────────────────────────────────────────────────────────────────────┘ ◄──────────────────────┘
```

- **CONNECTED packs hold the link** and poll over it: stage packs at `stagePollMs` (1.5 s), other
  held packs at `slowPollMs`. No disconnect while healthy → no churn.
- **CONNECTING failures** schedule a retry at the next backoff interval; success resets the backoff.
  After `failThreshold` consecutive failures the pack reads "out of range" (current behavior) but
  keeps being retried at the (now long) backoff interval.
- **Budget:** at most `maxHeldConnections` packs are CONNECTED/CONNECTING at once. Stage packs get
  slot priority. If the desired set (roster − disabled) exceeds the cap, the **overflow** packs
  rotate through leftover slots — connect, read once, disconnect, yield — at the slow cadence with
  backoff. (An 8-pack fleet with cap 8 never rotates.)
- **Link drop** → DISCONNECTED → backoff retry.
- **Disabled / Disconnect-all / Stop** → close those links cleanly (`BluetoothGatt.close()`).

**Scheduler as a pure function (the testable core):** the manager's decision logic is extracted so
it can be unit-tested without BLE:

```kotlin
// Given the world, decide actions for this tick. Pure — no BLE, no clocks beyond `now`.
fun planFleet(
    desired: Set<String>,            // roster − disabled
    stage: Set<String>,
    held: Set<String>,               // currently CONNECTED
    connecting: Set<String>,
    backoffUntil: Map<String, Long>, // per-pack next-eligible time
    maxHeld: Int,
    now: Long,
): FleetPlan   // { toConnect, toPoll(addr→intervalMs), toRotateOut, overflowRotate }
```

The async layer (GATT callbacks, the `BleSession`, the write/notify channel) executes the plan and
feeds results back (connected/failed/dropped/telemetry) — thin glue.

## Data flow

`MonitorEngine` → `FleetConnectionManager.start(... onPoll, onReachable)` (unchanged surface) →
manager selects the `BatteryProfile` per target (by name), opens/holds `BleSession`s per the plan,
writes `profile.statusFrame` at the tier cadence, and forwards every frame via `onPoll`
(raw + parsed-or-null, as today) → engine → `TelemetryRepository` + UI. `onReachable` reflects the
state machine (CONNECTED → reachable; backed-off-past-threshold → unreachable).

## Phasing

**Phase 1 — Battery Profile abstraction (behavior-preserving).**
Create `ble/profile/BatteryProfile.kt`, `TelemetryLayout`, `BackoffSpec`, `ProfileRegistry`, and the
one Redodo profile. Repoint `BmsProtocol.parseTelemetry`/frame-building and `BleScanner`/
`isCompatibleBmsName` to read from the profile. **No behavior change** — same UUIDs, offsets,
prefixes, cadences. Verified by the existing protocol/scanner unit tests still passing.

**Phase 2 — `FleetConnectionManager` (behavior change).**
Implement the per-pack state machine + `planFleet` scheduler + backoff, replacing the rotating
sampler in `BmsRepository`. Wire `MonitorEngine` to it (minimal — same callback surface). Adopt the
profile's write type and budget. Verified by `planFleet` unit tests + on-device (hold all 8, slow
off-stage poll, patient retry, clean disconnect-all).

## Testing

Pure JVM unit tests (the project's existing style):
- `profileFor(name)` — prefix matching (R-/L-/P-/… → Redodo; unknown → null), case-insensitive.
- `BackoffSpec` schedule — 5 s → 10 s → 20 s … capped at 120 s; reset on success.
- **`planFleet`** — the heart: budget respected (never > `maxHeld` connecting+held); stage packs get
  slots first; backed-off packs skipped until due; overflow rotates fairly; disabled packs never
  selected. Several scenarios (fleet ≤ cap → all held no rotation; fleet > cap → overflow rotates;
  all backed off → nothing connects; stage change re-prioritizes).
- `parseTelemetry` — re-run existing tests now that offsets come from the profile (same values).

BLE/GATT orchestration stays thin glue (Room/BLE need instrumentation) — validated by build +
on-device run.

## Risks & Mitigations

- **Controller connection limit.** The cap defaults to 8 (proven on the Pixel 6). If a device/adapter
  allows fewer, holding 8 could fail; the backoff + overflow-rotation path degrades gracefully (a
  pack that can't get a slot rotates). The cap is a profile constant, easy to lower.
- **Coexistence with the official app.** Holding all 8 occupies every pack's single client slot, so
  the Redodo app can't connect while we hold them. Mitigated by the existing **Disconnect-all** /
  per-pack disconnect (the explicit yield gesture); documented as such.
- **Refactor regressions (Phase 1).** Moving offsets/UUIDs into the profile risks a typo changing a
  parse. Mitigated by re-running the existing `BmsProtocolTest` against the profile-sourced values.
- **State-machine complexity.** Keeping the *decision* pure (`planFleet`) and the BLE *execution*
  thin bounds the risk and makes the hard part testable.

## Open Items for the Implementation Plan

- Exact `planFleet` overflow-rotation fairness policy (round-robin by least-recently-polled).
- Whether to keep `BmsProtocol` as a thin profile-bound facade or fold it entirely into the profile.
- Connection-parameter request (Android `requestConnectionPriority`) — adopt a relaxed/low-power
  priority to match Redodo's gentleness, or leave default (decide during planning).
