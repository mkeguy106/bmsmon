# WebUI v2 — Devices (in Settings) Design Spec

**Status:** approved design, ready for plan · **Date:** 2026-07-12
**Builds on:** WebUI v2 (all six views shipped). A small follow-on, not a roadmap phase.

Fold the device-admin functionality (enroll / list / revoke) into the v2 **Settings** view as a
**Devices** section — porting v1's `AdminDevices` into the v2 token idiom. No standalone nav
destination, no new backend, no new dependencies.

## 1. Goals & non-goals

**Goals**
- A **Devices** card inside `SettingsView` (desktop + mobile) with: enroll (mint code + QR),
  device list, revoke. Faithful port of v1 `AdminDevices`, restyled with v2 tokens.
- Remove the disabled "Devices — SOON" item from the v2 nav (it's now inside Settings).

**Non-goals**
- A separate Devices nav destination / `V2View` / bottom-tab (explicitly folded into Settings).
- Any new backend endpoint or npm dependency, or new device-admin capabilities beyond v1's.

## 2. Component — `web/src/v2/components/DevicesPanel.tsx`

Port of `web/src/components/AdminDevices.tsx`, same behavior, v2 styling:
- **State/logic reused verbatim** (they're framework-agnostic): `getDevices`/`mintCode`/`revokeDevice`
  from `web/src/api.ts`; `DeviceRow` from `web/src/types.ts`; `QRCode.toDataURL` from `qrcode`.
- **Enroll:** an "Enroll device" button → `mintCode()` → shows the 6-char code (mono, `--ok`) + a QR
  (`QRCode.toDataURL(JSON.stringify({ base: window.location.origin, code }), { width: 260, margin: 1 })`,
  rendered on a white padded background so it scans in dark mode), plus the "Cloud sync → Scan QR ·
  expires 10 min" instruction and the server origin.
- **List:** a table (label ?? install_uuid · last-seen · Revoke button); revoked rows dimmed.
- **Errors:** reuse v1's `errKind` (401/403 → auth, else net). Auth → "Not authorized — your session
  may have expired (admin required). Reload to sign in again." Net load error → message + Retry;
  mint/revoke net error → inline message. (This is the non-admin path too — the endpoints are
  `require_admin`, so a non-admin viewer sees the auth message rather than a broken panel.)
- **Styling:** v2 tokens only — `.card`/`.eyebrow`, `--panel-2` for the QR/code block background,
  `--border`, `--text`/`--text-2`/`--text-3`/`--text-4`, `--ok` for the code, `--live` for errors,
  `--nav-active` for buttons. No v1 `theme.css` vars (`--accent`/`--card`/`--critical`/`--input-bg`).

## 3. `SettingsView.tsx`

Add a **DEVICES** `.card` (eyebrow "DEVICES") rendering `<DevicesPanel />`, placed after Appearance
and before About (device admin sits near the bottom, above the static About). No other change to the
existing Units / Journey Map / Appearance / About cards.

## 4. `nav.ts`

Remove the disabled MANAGE-group item `{ view: "command" as V2View, label: "Devices", icon: "cpu", disabled: true }`.
The MANAGE group becomes **Alerts · Settings**. No `V2View` change (Devices was never a real view).
`Nav`/`BottomTabs` need no change (they derive from `NAV_GROUPS`); the `nav.test.ts` expectation that
asserts a disabled "Devices" item must be updated to the new group shape.

## 5. Testing & verification

- **Web (vitest):** update `nav.test.ts` (MANAGE group is now Alerts · Settings; no disabled item).
  `DevicesPanel` is presentational/side-effecting (fetches) — no new unit test beyond a tsc/build
  check; its logic is the already-shipped v1 behavior.
- **End-to-end (verify skill):** build; drive `/v2/` → Settings shows the Devices card; "Enroll
  device" mints a code + renders a QR against the running backend; the device list loads; revoke
  works; a non-admin/expired session shows the auth message. Other views + v1 unaffected.

## 6. Out of scope / open items

- Device rename/label editing, per-device detail, last-seen relative formatting — keep v1's raw
  fields (a relative "last seen" is a nice-to-have; render the raw `last_seen_at` as v1 does unless
  trivial).
- v1's `AdminDevices` stays as-is (v1 is untouched); this is a parallel v2 port.
