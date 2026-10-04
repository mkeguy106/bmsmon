import { describe, expect, it } from "vitest";
import {
  outingWh, drainedPct, baseBand, bandStatus, efficiencySummary, everyPackLive, projectionLive,
  MIN_OUTING_MI,
} from "./efficiency";
import { mergeBaseTracks } from "./journey";
import type { TrackPoint } from "../track";
import type { RangeParams } from "../../range";
import type { FleetItem } from "../../types";
import { groupBases } from "../fleet";
import { baseView } from "./baseView";

const p = (o: Partial<TrackPoint>): TrackPoint =>
  ({ t: 0, lat: 43, lon: -87.9, power_w: 0, current_a: 0, soc: 88, acc: null, ...o });

const S = 1000, MIN = 60_000;
const params = (o: Partial<RangeParams>): RangeParams => ({
  whPerDay: { lo: 78, hi: 182 }, activeW: { lo: 52.5, hi: 97.5 },
  whPerMile: { lo: 50, hi: 100 }, learnedDays: 5, updatedMs: 0, ...o,
});

describe("outingWh", () => {
  it("integrates |power| over discharging buckets only", () => {
    // Three 15 s steps, all discharging at 240 W → 240 W × (45 s) = 3 Wh.
    const pts = [0, 15, 30, 45].map((s) => p({ t: s * S, power_w: -240, current_a: -20 }));
    expect(outingWh(pts)).toBeCloseTo(3, 5);
  });
  it("skips idle/charging buckets (current above the discharge threshold)", () => {
    const pts = [
      p({ t: 0, power_w: -240, current_a: -20 }),
      p({ t: 15 * S, power_w: 0, current_a: 0 }),       // idle — not counted
      p({ t: 30 * S, power_w: 500, current_a: 40 }),    // charging — not counted
    ];
    expect(outingWh(pts)).toBe(0);
  });
  it("caps a bucket's Δt at 60 s so a gap can't inflate energy", () => {
    // A 1 h gap at 360 W would be 360 Wh uncapped; capped at 60 s → 6 Wh.
    const pts = [p({ t: 0, power_w: -360, current_a: -30 }), p({ t: 60 * MIN, power_w: -360, current_a: -30 })];
    expect(outingWh(pts)).toBeCloseTo(6, 5);
  });
  it("is zero for a single point", () => {
    expect(outingWh([p({ current_a: -20, power_w: -240 })])).toBe(0);
  });
  // A series pair carries one current, so a bucket one pack is missing from holds about half
  // the base's power. Counting it as-is made the outing look cheap, and "miles left at today's
  // rate" high (final review C1).
  it("scales a bucket a pack is missing from up to the whole base", () => {
    const pts = [
      p({ t: 0, power_w: -240, current_a: -20, packs: 2 }),
      p({ t: 15 * S, power_w: -240, current_a: -20, packs: 2 }),  // both packs: 1 Wh
      p({ t: 30 * S, power_w: -120, current_a: -10, packs: 1 }),  // one of two: 0.5 Wh → 1 Wh
    ];
    expect(outingWh(pts, 2)).toBeCloseTo(2, 5);
    expect(outingWh(pts)).toBeCloseTo(1.5, 5);  // no base size given: as merged
  });
});

describe("drainedPct", () => {
  it("first minus last SOC", () => {
    expect(drainedPct([p({ soc: 90 }), p({ soc: 71 })])).toBe(19);
  });
  it("null when flat or charging (non-positive drop)", () => {
    expect(drainedPct([p({ soc: 80 }), p({ soc: 80 })])).toBeNull();
    expect(drainedPct([p({ soc: 70 }), p({ soc: 85 })])).toBeNull();
  });
  it("ignores null-soc points and needs at least two known", () => {
    expect(drainedPct([p({ soc: null }), p({ soc: 90 })])).toBeNull();
    expect(drainedPct([p({ soc: 90 }), p({ soc: null }), p({ soc: 60 })])).toBe(30);
  });
});

describe("baseBand", () => {
  it("sums each connected pack's whPerMile band", () => {
    expect(baseBand([params({ whPerMile: { lo: 50, hi: 100 } }), params({ whPerMile: { lo: 40, hi: 90 } })]))
      .toEqual({ lo: 90, hi: 190 });
  });
  it("drops invalid/non-positive bands, null when none survive", () => {
    expect(baseBand([params({ whPerMile: { lo: 0, hi: 100 } })])).toBeNull();
    expect(baseBand([params({ whPerMile: { lo: NaN, hi: 5 } })])).toBeNull();
    expect(baseBand([])).toBeNull();
  });
});

describe("bandStatus", () => {
  const band = { lo: 90, hi: 190 };
  it("below / inside / above at the boundaries", () => {
    expect(bandStatus(89.9, band)).toBe("below");
    expect(bandStatus(90, band)).toBe("inside");
    expect(bandStatus(140, band)).toBe("inside");
    expect(bandStatus(190, band)).toBe("inside");
    expect(bandStatus(190.1, band)).toBe("above");
  });
});

describe("efficiencySummary", () => {
  // Track: 4 buckets @ 240 W discharge over 45 s = 3 Wh; caller supplies the miles.
  // Merged over both packs of a two-pack base (mergeBaseTracks sets packs).
  const track = [0, 15, 30, 45].map((s) => p({ t: s * S, power_w: -240, current_a: -20, soc: 90 - s / 15, packs: 2 }));

  it("computes base-total cost, band status, and drain on a past day", () => {
    const r = efficiencySummary({
      points: track, activeMiles: 1, packParams: [params({}), params({})],
      usableWh: 1280, charging: false, live: false, packCount: 2, everyPackLive: true,
    });
    expect(r.wh).toBeCloseTo(3, 5);
    expect(r.costPerMile).toBeCloseTo(3, 5);       // 3 Wh / 1 mi
    expect(r.band).toEqual({ lo: 100, hi: 200 });  // two packs summed
    expect(r.status).toBe("below");                // 3 « 100
    expect(r.drainedPct).toBe(3);
    expect(r.seed).toBe(false);
    expect(r.milesAtTodayRate).toBeNull();         // not live → no projection
    expect(r.milesAtUsualRate).toBeNull();
  });

  it("gates cost below MIN_OUTING_MI", () => {
    const r = efficiencySummary({
      points: track, activeMiles: MIN_OUTING_MI - 0.01, packParams: [params({})],
      usableWh: 640, charging: false, live: false, packCount: 2, everyPackLive: true,
    });
    expect(r.costPerMile).toBeNull();
    expect(r.status).toBeNull();
  });

  it("projects miles left from the usable base energy when live and discharging", () => {
    const r = efficiencySummary({
      points: track, activeMiles: 1, packParams: [params({ whPerMile: { lo: 2, hi: 4 } }), params({ whPerMile: { lo: 2, hi: 4 } })],
      usableWh: 1280, charging: false, live: true, packCount: 2, everyPackLive: true,
    });
    // usable = 2 packs × 50 Ah × 12.8 V = 1280 Wh. today rate = 3 Wh/mi → 426.7 mi.
    expect(r.milesAtTodayRate).toBeCloseTo(1280 / 3, 3);
    // usual mid = (4 + 8)/2 = 6 Wh/mi → 213.3 mi.
    expect(r.milesAtUsualRate).toBeCloseTo(1280 / 6, 3);
  });

  it("suppresses the projection while charging", () => {
    const r = efficiencySummary({
      points: track, activeMiles: 1, packParams: [params({})],
      usableWh: 640, charging: true, live: true, packCount: 2, everyPackLive: true,
    });
    expect(r.milesAtTodayRate).toBeNull();
    expect(r.milesAtUsualRate).toBeNull();
  });

  it("flags a seed band when any connected pack is unlearned", () => {
    const r = efficiencySummary({
      points: track, activeMiles: 1, packParams: [params({ learnedDays: 5 }), params({ learnedDays: 0 })],
      usableWh: 1280, charging: false, live: false, packCount: 2, everyPackLive: true,
    });
    expect(r.seed).toBe(true);
  });

  // Review Focus: an empty base is a real answer ("~0 mi left"), not a missing one.
  it("projects 0 miles for an empty base instead of nothing", () => {
    const r = efficiencySummary({
      points: track, activeMiles: 1, packParams: [params({})],
      usableWh: 0, charging: false, live: true, packCount: 2, everyPackLive: true,
    });
    expect(r.milesAtTodayRate).toBe(0);
    expect(r.milesAtUsualRate).toBe(0);
  });

  it("projects nothing when the usable energy is unknown", () => {
    const r = efficiencySummary({
      points: track, activeMiles: 1, packParams: [params({})],
      usableWh: null, charging: false, live: true, packCount: 2, everyPackLive: true,
    });
    expect(r.milesAtTodayRate).toBeNull();
    expect(r.milesAtUsualRate).toBeNull();
  });
});

// Journey feeds efficiencySummary from the base view-model. When any pack lacks a usable
// capacity the view has no usable energy, and the card must project nothing (never a number
// from the known pack alone).
describe("Journey with a pack that reports no capacity", () => {
  const it2 = (address: string, ah: number | null): FleetItem => ({
    address, group_id: "2012", alias: `2012 · ${address}`, ts_ms: 1_000,
    soc: 60, remaining_ah: ah, current_a: -5, power_w: -64, temp_c: 22, regen: false,
  } as FleetItem);
  const project = (a: number | null, b: number | null, stale: string[] = []) => {
    const view = baseView(groupBases([it2("A", a), it2("B", b)], new Set(stale))[0],
      { rangeParams: new Map(), tempConfig: null });
    const pts = [0, 15, 30].map((s) => p({ t: s * S, power_w: -240, current_a: -20, packs: 2 }));
    return { view, eff: efficiencySummary({
      points: pts, activeMiles: 1, packParams: view.packParams, usableWh: view.usableWh,
      charging: view.charging, live: projectionLive(true, view),
      packCount: view.packs.length, everyPackLive: everyPackLive(view),
    }) };
  };
  it("no-data: no usable energy, no projection, no last-known note", () => {
    const { view, eff } = project(55, null);
    expect(view.range.kind).toBe("no-data");
    expect(view.usableWh).toBeNull();
    expect(view.usableLastKnown).toBeNull();
    expect(eff.milesAtTodayRate).toBeNull();
    expect(eff.milesAtUsualRate).toBeNull();
  });
  it("no live pack: no projection, matching Command's offline state", () => {
    const { view, eff } = project(55, 55, ["A", "B"]);
    expect(view.livePacks).toHaveLength(0);
    expect(eff.milesAtTodayRate).toBeNull();
    expect(eff.milesAtUsualRate).toBeNull();
  });
  it("projectionLive: needs a live window and a live pack", () => {
    const off = project(55, 55, ["A", "B"]).view;
    const on = project(55, 55).view;
    expect(projectionLive(true, off)).toBe(false);
    expect(projectionLive(true, on)).toBe(true);
    expect(projectionLive(false, on)).toBe(false);
    expect(projectionLive(true, null)).toBe(false);
  });
  it("both packs reporting still projects", () => {
    const { eff } = project(55, 55);
    expect(eff.milesAtTodayRate).not.toBeNull();
  });
});

// Final review C1: "~X mi left at today's rate" divides the WHOLE base's usable energy (live
// and last-known packs) by a cost per mile from the merged track, which holds only the packs
// that sent GPS samples. With a pack out of range the cost read half and the projection 2×
// high. Today's rate now needs every pack live AND a track that covers every pack.
describe("today's rate needs every pack live and in the track", () => {
  const T0 = 10 * 60 * MIN;
  const item = (address: string, tsMs: number): FleetItem => ({
    address, group_id: "2012", alias: `2012 · ${address}`, ts_ms: tsMs,
    soc: 50, remaining_ah: 50, current_a: -10, power_w: -120, temp_c: 22, regen: false,
  } as FleetItem);
  // The probe: both packs hold 50 Ah, the outing was 2 mi at a true 60 Wh/mi base (each pack
  // 120 W over 30 min = 60 Wh, 120 Wh in all).
  const bucketsOf = (address: string, from: number, to: number) => ({
    address,
    points: Array.from({ length: (to - from) / (15 * S) + 1 }, (_, i) =>
      p({ t: from + i * 15 * S, power_w: -120, current_a: -10 })),
  });
  const outing = (bTrack: ReturnType<typeof bucketsOf> | null, bStale: boolean) => {
    const now = T0 + 30 * MIN;
    const base = groupBases(
      [item("A", now), item("B", bStale ? now - 40 * MIN : now)], new Set(bStale ? ["B"] : []))[0];
    const view = baseView(base, { rangeParams: new Map(), tempConfig: null });
    const tracks = [bucketsOf("A", T0, T0 + 30 * MIN), ...(bTrack ? [bTrack] : [])];
    return efficiencySummary({
      points: mergeBaseTracks(tracks), activeMiles: 2, packParams: view.packParams,
      usableWh: view.usableWh, charging: view.charging, live: projectionLive(true, view),
      packCount: view.packs.length, everyPackLive: everyPackLive(view),
    });
  };

  it("both packs live and in the track: today's rate from the whole base's cost", () => {
    const eff = outing(bucketsOf("B", T0, T0 + 30 * MIN), false);
    expect(eff.costPerMile).toBeCloseTo(60, 5);
    expect(eff.milesAtTodayRate).toBeCloseTo(1280 / 60, 5);   // ~21 mi
    expect(eff.todayRateWithheld).toBeNull();
  });

  it("the reviewer's probe: B stale for 40 min, absent from the track → no today's rate", () => {
    const eff = outing(null, true);
    expect(eff.milesAtTodayRate).toBeNull();                  // was ~43 mi, 2× the truth
    expect(eff.todayRateWithheld).toBe("pack-not-live");
    expect(eff.milesAtUsualRate).not.toBeNull();             // band-based, may stay
  });

  it("every pack live but one pack's track is missing (a failed fetch) → no today's rate", () => {
    const eff = outing(null, false);
    expect(eff.milesAtTodayRate).toBeNull();
    expect(eff.todayRateWithheld).toBe("track-incomplete");
    expect(eff.milesAtUsualRate).not.toBeNull();
  });

  it("a pack that dropped out mid-outing and came back never raises today's rate", () => {
    // B reported only for the last 5 min of the 30 min outing.
    const eff = outing(bucketsOf("B", T0 + 25 * MIN, T0 + 30 * MIN), false);
    const full = outing(bucketsOf("B", T0, T0 + 30 * MIN), false);
    expect(eff.milesAtTodayRate).not.toBeNull();
    expect(eff.milesAtTodayRate!).toBeLessThanOrEqual(full.milesAtTodayRate! + 1e-9);
  });

  it("a past-day summary carries no withheld reason (there is no projection to withhold)", () => {
    const past = efficiencySummary({
      points: mergeBaseTracks([bucketsOf("A", T0, T0 + 30 * MIN)]), activeMiles: 2,
      packParams: [params({}), params({})], usableWh: 1280, charging: false, live: false,
      packCount: 2, everyPackLive: false,
    });
    expect(past.todayRateWithheld).toBeNull();
  });
});
