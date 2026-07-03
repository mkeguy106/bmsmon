# Low pack seizes the stage + fleet-wide alarm

**Date:** 2026-07-02
**Status:** Design — pending user approval

## Motivation

A LiFePO4 pack that discharges too far can be damaged. Today the app only alarms
(flash + headless notification) for the **one base currently on the main stage** — a
background pack that drifts low is invisible until you happen to look at *All Batteries*.
The rule we want: **any pack in the fleet that falls to the alert threshold is immediately
pulled onto the main stage and alarms**, and because only one base can hold the stage at a
time, **every** low pack additionally fires its own notification so a second low pack is
never masked by the first.

## Trigger — reuse the existing alert ladder

We do **not** add a new threshold knob. "Low" reuses the existing capacity alert ladder
(`Settings › Alerts`):

- A pack qualifies as **low** when `alertsOn` **and** its last-known reachable
  `SOC ≤ seizeThreshold`, where **`seizeThreshold = max(enabledThresholds)`** — the highest
  enabled ladder rung. With the default ladder (`30/25/20/15/10/5`) that is **30%**.
- Only **reachable** packs with real telemetry qualify. A pack that has dropped off BLE
  can't be judged on live SOC — it keeps rendering DISCONNECTED as today. (Unavoidable
  limit: no wireless read = no reading.)
- `alertsOn = false` disables the whole thing (seize + notifications), consistent with
  "reuse the ladder."

## Behavior decisions (locked with user)

| Decision | Choice |
|----------|--------|
| Stage precedence | **Override everything** — a low pack seizes the stage over the actively-discharging chair AND over a manual pin. |
| Threshold | Reuse the ladder; default 30%; configurable via the existing ladder. |
| Scope | Android **and** WebUI. |
| Multi-pack coverage | **Fleet-wide notifications** — lowest low pack seizes the stage + flash; every low pack fires its own deduped notification. |
| WebUI threshold source | **Sync to cloud** — the phone pushes the capacity seize threshold; the web mirrors it (falls back to 30% when absent). |

## Architecture

### 1. Android — stage seize (`model/Fleet.kt`, pure)

`resolveStage()` gains a pre-emptive **low-pack override** that runs **before** the manual-pin
branch (so it beats the pin, satisfying "override everything"):

- New nullable field on `StageInputs`: `seizeThreshold: Int?`
  (`= if (alertsOn && enabledThresholds.isNotEmpty()) max else null`, computed in the VM).
- When non-null, scan `fleet` for entries where `reachable == true` and
  `telemetry.soc <= seizeThreshold`. If any exist, pick the **lowest SOC** (daily-driver id
  breaks exact ties), find that address's group, and return `StageTarget.Base(group.id)`.
  If the low pack is not in any group, return `StageTarget.Single(address)`.
- Charging does **not** block the seize (a low pack on the charger is still surfaced), but the
  existing charging-suppression hysteresis still suppresses the **flash** — no code change
  there; the flash path already keys off charging.
- When the pack recovers above `seizeThreshold`, the override yields and resolution falls
  through to the existing pin/active/hold/charging/idle ladder — the manual pin (still set)
  is restored automatically.

`resolveStage` stays pure and fully unit-testable. New `StageInputs` field is threaded from
`refresh()` in `BatteryViewModel` (it already has `alertsOn` / `enabledThresholds`).

**Interaction with `pinned`:** while a low pack is seized, `resolved != manualStage`, so the
existing `isPinned` computation already reads `false` and the header shows the auto/seized
state — no extra work.

### 2. Android — new setting: "Pull low packs to stage"

The user asked for a setting. Since the *threshold* reuses the ladder, the one new knob gates
the **visual seize** (the potentially-intrusive stage yank) independently of the alarm:

- New pref `seizeLowToStage: Boolean` (default **ON**) in `SettingsStore` + `UiState`.
- Surfaced in `Settings › Alerts` as a toggle.
- Gates only the seize: when OFF, `seizeThreshold` is passed as `null` to `resolveStage`, but
  fleet-wide notifications (below) still fire — you keep the alarm, lose the auto-switch.

### 3. Android — fleet-wide notifications (`monitor/MonitorEngine.kt`, `monitor/AlertNotifier.kt`)

Today `evaluateAlerts()` evaluates only `stageAddrs`. Change it to evaluate **every reachable
pack**:

- For each reachable pack with telemetry, build `PackSoc` and run the existing
  `evalStageAlert(listOf(pack), cfg)` (per-pack — the ladder logic is unchanged).
- `AlertNotifier` becomes **per-address**:
  - `lastNotified: MutableMap<String, Int?>` keyed by address (was a single field).
  - Distinct notification id per pack (e.g. `NOTIF_CAP_BASE + stableIndex(address)`), so
    two low packs show two notifications instead of overwriting.
  - Per-address charge-hold latch (`Map<String, Long>`) mirroring the existing
    `nextChargeHold` hysteresis, so a background pack flapping Idle↔Charging at a charger
    can't strobe its notification.
  - Notification text names the **pack** (alias) instead of "Stage at N%".
- The **in-app flash** (`BatteryViewModel.stageAlert()`) is unchanged — the seized low pack
  IS the stage, so it flashes through the existing stage-driven path.
- The stage pack's notification is now just the fleet-wide case for the staged address — no
  special-casing.

Temperature notifications are untouched (still worst-stage-pack driven — out of scope).

### 4. Android — push the capacity seize threshold to the cloud

Mirror the existing one-way temp-config push (`cloud/CloudJson.kt`, `TelemetryReporter`,
`POST /api/v1/config`):

- New serializable `CapacityConfigJson(seize_soc: Int, alerts_on: Boolean, updated_at_ms: Long)`.
- The config push body gains an optional `capacity` object alongside the existing temp fields
  (the endpoint already reads a signed/gzipped JSON body — we extend the model, keeping temp
  fields as-is so older bodies still validate).
- Enqueued on the same durable, latest-wins channel as temp config, and re-enqueued whenever
  the ladder or `alertsOn` changes (hook into the existing `setThresholds` / `setAlertsOn`
  paths next to `enqueueTempConfig`).

### 5. Server (`server/app/`)

- **Schema** (`db/schema.sql`, idempotent): new `device_alert_config` table — device-level,
  latest-wins: `device_id (PK)`, `seize_soc int`, `alerts_on bool`, `updated_at_ms bigint`.
  (Device-level, not per-profile — the capacity ladder is one setting per device, unlike temp
  which is per profile.)
- **`POST /api/v1/config`** (`routers/api_device.py`): when the body carries the `capacity`
  block, `upsert_alert_config(device_id, …)` in addition to the existing temp upsert. Same
  auth / gzip / body-hash verify.
- **`GET /web/alert-config`** (`routers/web.py`, `current_user`): read-only mirror returning
  the latest `seize_soc` / `alerts_on` across devices (parallel to `/web/temp-config`).
- New queries in `db/queries.py`: `upsert_alert_config`, `get_alert_config`.

### 6. WebUI (`web/src/`)

- `api.ts`: fetch `/web/alert-config` (poll alongside the existing temp-config poll).
- `stage.ts` — `selectStageItems` gains a low-pack override at the top: among **fresh**
  (non-stale) items, if any has `soc <= seizeThreshold`, the lowest such pack's **group**
  becomes the stage (over pins and auto). Threshold comes from the synced alert-config,
  **falling back to `30` and `alerts_on !== false`** when the mirror is absent. Stale/muted
  packs never drive it (can't trust stale SOC).
- `MainStage.tsx`: a small **"LOW"** marker on the stage when it's showing a seized low pack.
- No audible alarm (browser limitation) — visual only, matching the WebUI's mirror role.

## Testing

Pure logic carries the risk; all of it is unit-testable without BLE/network:

- **`resolveStage` seize** (`FleetLogicTest` / new cases): seizes over active discharge;
  seizes over a manual pin; lowest-SOC pack wins among several low; daily-driver breaks ties;
  unreachable low pack is ignored; charging low pack still seizes; recovery falls back to
  pin/auto; `seizeThreshold = null` (setting off / alerts off) disables it.
- **Per-address notification dedup** (`AlertsTest`): `nextNotifyDecision` per address fires
  once per pack, escalates independently, cancels on recovery/charging; two low packs produce
  two independent decisions.
- **WebUI `selectStageItems`** (`stage`/new test): low fresh pack overrides pins + auto;
  stale low pack ignored; fallback threshold 30 when config absent.
- **Server**: `upsert_alert_config` latest-wins; `/web/alert-config` returns latest;
  `/api/v1/config` with a `capacity` block upserts and still handles temp-only bodies.

## Out of scope

- Temperature-alert fleet-wide fan-out (stays stage-worst driven).
- Any change to which command bytes are sent to the BMS — read-only protocol unchanged.
- Recovering SOC for packs that are off BLE (physically impossible to read).
