import { describe, expect, it } from "vitest";
import {
  outingWh, drainedPct, baseBand, bandStatus, efficiencySummary, MIN_OUTING_MI,
} from "./efficiency";
import type { TrackPoint } from "../track";
import type { RangeParams } from "../../range";

const p = (o: Partial<TrackPoint>): TrackPoint =>
  ({ t: 0, lat: 43, lon: -87.9, power_w: 0, current_a: 0, soc: 88, ...o });

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
  const track = [0, 15, 30, 45].map((s) => p({ t: s * S, power_w: -240, current_a: -20, soc: 90 - s / 15 }));

  it("computes base-total cost, band status, and drain on a past day", () => {
    const r = efficiencySummary({
      points: track, activeMiles: 1, packParams: [params({}), params({})],
      remainingAh: [50, 50], charging: false, live: false,
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
      remainingAh: [50], charging: false, live: false,
    });
    expect(r.costPerMile).toBeNull();
    expect(r.status).toBeNull();
  });

  it("projects miles left when live and discharging (base-total energy basis)", () => {
    const r = efficiencySummary({
      points: track, activeMiles: 1, packParams: [params({ whPerMile: { lo: 2, hi: 4 } }), params({ whPerMile: { lo: 2, hi: 4 } })],
      remainingAh: [50, 50], charging: false, live: true,
    });
    // baseRemWh = (50+50) × 12.8 = 1280. today rate = 3 Wh/mi → 426.7 mi.
    expect(r.milesAtTodayRate).toBeCloseTo(1280 / 3, 3);
    // usual mid = (4 + 8)/2 = 6 Wh/mi → 213.3 mi.
    expect(r.milesAtUsualRate).toBeCloseTo(1280 / 6, 3);
  });

  it("suppresses the projection while charging", () => {
    const r = efficiencySummary({
      points: track, activeMiles: 1, packParams: [params({})],
      remainingAh: [50], charging: true, live: true,
    });
    expect(r.milesAtTodayRate).toBeNull();
    expect(r.milesAtUsualRate).toBeNull();
  });

  it("flags a seed band when any connected pack is unlearned", () => {
    const r = efficiencySummary({
      points: track, activeMiles: 1, packParams: [params({ learnedDays: 5 }), params({ learnedDays: 0 })],
      remainingAh: [50, 50], charging: false, live: false,
    });
    expect(r.seed).toBe(true);
  });
});
