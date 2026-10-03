# Open check-in items

The single dated list of open measurement, verification and decision items for bmsmon. Each item
says what "done" means and where its method lives. When an item closes, delete it here and record
the result where durable knowledge belongs: CLAUDE.md for constants and invariants, or a dated
`docs/calibration-checkin-YYYY-MM-DD.md` write-up for a check-in pass. Last reviewed: 2026-10-02.

**Phone rules for every on-device item** (CLAUDE.md, "The Pixel is a dedicated telemetry device"):
ADB reaches the Pixel only on home Wi-Fi; airplane mode stays **ON** with Wi-Fi and Bluetooth
re-enabled on top; never toggle radios right before an outing; never uninstall `dev.joely.bmsmon`;
after any install, `am start` the activity — the screen is the product.

## Calibration check-in #3 — due 2026-10-16 (overdue: was due ~2026-09)

| # | Item | Done when | Method / basis |
|---|---|---|---|
| 1 | **AR power-cost keep/revert.** Revert condition stands: if periodic Activity Recognition costs more than the GPS pause saves, the motion gate is a net loss and reverts. | Decision recorded (keep or revert) with ≥3 comparable unplugged, screen-normalized stretches against the 2026-08-03 baseline, plus GNSS duty from batterystats. | `docs/ar-power-cost-protocol.md` (day-0 baseline 2026-08-09; 2026-08-10 is the first comparable day) |
| 2 | **Motion-column wire cost.** The four motion columns ride every pack's row (8× per reading). | Real gzipped batch-size delta measured; if material, adopt the documented fallback (populate them only on the staged base's rows). | CLAUDE.md, the motion-state paragraph under "Cloud Server & Deployment" |
| 3 | **Re-quantify the GNSS-off duty and saving under the silence-as-stillness gate.** The recorded ≈15 mA assumed the discharge-only era's 68.4 % duty; the new gate holds GNSS off through whole parked nights. | New duty % and mA figure recorded in CLAUDE.md's battery-saver section. | `docs/calibration-checkin-2026-08-04.md` §6 (duty from `PARKED_HOLD_MS` minutes), cross-checked with prod `motion_still` rows |
| 4 | **whPerMile re-verify** on the corrected current-sign basis as outing days accumulate. | Learned bands recomputed from the cloud dataset and compared with `device_range_config`; seeds left alone unless they disagree materially. | `docs/calibration-checkin-2026-08-04.md` §2 |
| 5 | **Charge finish-hour histogram** (standing trigger for the depth-aware charge tail). | Histogram re-run; reopen the depth-aware tail only if sessions start finishing 08:00–23:59 from a low start SOC. | `docs/calibration-checkin-2026-08-04.md` §4 |

Write the pass up as `docs/calibration-checkin-YYYY-MM-DD.md`, like 2026-08-04.

## Calibration check-in #4 — due 2026-11-16

Re-run the standing items (4 and 5 above) plus whatever #3 leaves open, and re-verify the gauge
constants the way the 2026-08-04 pass did (`POWER_RING_FULL_W`, `REGEN_EPS`/`REGEN_WINDOW_MS`,
`SEED_TAIL_MIN`, GPS gates).

## Opportunistic — next time the code is touched

- **Settings › Battery saver "Motion sensing active" line:** both permission states (granted /
  denied) have never been confirmed on-device. Done when each state has been seen on the Pixel.
- **`foldMotion` test:** pin "a run starts at the reading's own `atMs`" (immediate close on an old
  reading) next time `model/BatterySaver.kt` is edited.
- **Cosmetic deferrals** from `.superpowers/HANDOVER-2026-08-10.md` item 6 (`_clip_motion_at`
  lower-bound test, a long `sampleJson` line, two phrasing imprecisions, `MotionSource` late-callback
  guard) — batch with adjacent work; never blocking.

## Architecture review follow-ups

The 2026-10-02 review's Tier 2–4 roadmap (and the status of every Tier-1 item) is tracked in
`docs/architecture-review-2026-10-02.md` §Fix roadmap — that table, with its Status column, is the
tracker; don't duplicate its rows here. Its one-off sizing checks are listed there under "Read-only
checks the owner can run".
