# AR power-cost measurement protocol (open item 3.2)

**Question:** does periodic Activity Recognition (plus the motion gate it feeds) cost more than the
GPS pause saves? **Revert condition (standing):** if AR's cost exceeds the pause's saving, the
motion-gate feature is a net loss and should be reverted.

**Status 2026-08-09:** protocol defined, baseline captured, measurement runs opportunistically —
decision due at the ~2026-09 calibration check-in.

## Why the obvious measurements don't work

- **Per-uid attribution is blind:** AR executes inside Play Services; nothing accrues to
  `dev.joely.bmsmon`'s uid. The 18 h arprobe run (2026-08-04→05) produced a null result, not a
  number, for exactly this reason (and the phone sat on the charger throughout).
- **Historical batterystats is gone:** it resets at each full charge, so the pre-AR eras can't be
  re-read. What survives are the recorded 2026-08-03 measurements in `CLAUDE.md`: screen
  ~139 mA, cpu ~42 mA, GNSS ~22 mA, wifi ~16.5 mA, BT ~3 mA, against a 2 580 mAh / 6.5 h session.
- **The regimes moved under the measurement twice:** discharge-only pause (08-03→08-06, no AR,
  GNSS off 68.4% duty), motion gate v1 (08-06→08-09, AR on, gate never closed → GNSS ~always on),
  silence-as-stillness (08-09→, AR on, gate expected to close for most genuinely-parked time).

## Protocol

Compare **whole-phone discharge rate** across comparable unplugged stretches, screen-normalized,
between the current regime and the recorded 2026-08-03 baseline. The logic: the
silence-as-stillness gate should restore GNSS-off duty to at least the discharge-only era's 68.4%,
so if today's screen-normalized drain sits at or below the 08-03 baseline minus the ~15 mA pause
saving, AR is cheaper than the saving (keep); if it sits materially above, AR (or the gate) is
eating the saving (investigate, then revert per the standing condition).

Each ADB-reachable day, ideally after an unplugged stretch:

```bash
SERIAL=adb-1C091FDF6003V0-RQzUxy._adb-tls-connect._tcp
adb -s $SERIAL shell dumpsys battery | grep -E "level|status|Charge counter"
adb -s $SERIAL shell dumpsys batterystats --charged | grep -E \
  "Time on battery|Discharge amount|Amount discharged while screen off"
adb -s $SERIAL shell 'dumpsys batterystats --charged | grep -A2 gnss'
date +%s%3N
```

Record: on-battery time, total/screen-off discharge %, gnss time (the duty-cycle check — this is
how we verify the gate is actually delivering GNSS-off time, independent of the saving math), and
the charge counter + wall clock for spot-rate checks between two reads.

**Confounder control:** compare like with like — unplugged, screen mostly off stretches (overnight
off-charger is ideal but rare; the phone lives on the pad at night). Screen-on time dominates
(~139 mA), so never compare stretches with materially different screen duty; normalize by
subtracting `139 mA × screen-on fraction` before comparing. Charging-pad stretches are unusable
(pad thermal feedback swamps 15 mA effects — the 60 Hz measurement already proved this).

**Decision inputs at the ~2026-09 check-in:**
1. GNSS duty under the new gate (from batterystats gnss time; sanity-checked against prod
   `motion_still` rows) — confirms the saving exists to be protected.
2. Screen-normalized mA across ≥3 comparable stretches vs the 08-03 baseline.
3. If the two disagree materially in the costly direction and no other change explains it, that is
   the revert signal.

## Baseline (day 0 of the silence-as-stillness regime)

Captured 2026-08-09 ~13:42 UTC, minutes after the silence-as-stillness APK install:

- Battery 80%, charge counter 3 064 000 µAh, wall clock 1786324927238.
- On the wireless pad but `status=NOT_CHARGING` — consistent with the Pixel's 80 % adaptive-charge
  cap rather than the 2026-08-06 dead-pad failure mode; re-check on the next read (if it still
  reads NOT_CHARGING while *below* ~78 %, suspect the pad).
- batterystats session only 44 m 53 s old (reset by the pad), all screen-off, 3 % discharged in
  that window — high, but this window spans the gate-broken era (GNSS pinned on) plus two app
  restarts; not usable as a comparison stretch, recorded only as the session anchor.
- Historical anchors for comparison: 2 580 mAh / 6 h 33 m (2026-08-03, screen-on dominated);
  −174 mA on-pad at 11 % (the incident that started the battery-saver work); pause saving
  ≈ 0.684 × 22 mA ≈ 15 mA at the discharge-only era's duty.
