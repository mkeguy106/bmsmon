import { describe, expect, it } from "vitest";
import {
  SEED_RANGE_PARAMS, estimatePackRange, formatRangeLine, minRange, selectRangeParams,
  type PackRange, type RangeConfigRow, type RangeParams,
} from "./range";

// SHARED VECTORS: android RangeEstimateTest.kt asserts the same numbers — keep in sync.
const params: RangeParams = {
  whPerDay: { lo: 100, hi: 180 }, activeW: { lo: 70, hi: 100 }, whPerMile: { lo: 18, hi: 24 },
  learnedDays: 10, updatedMs: 0,
};

describe("estimatePackRange", () => {
  it("computes all three bands (70 Ah × 12.8 V = 896 Wh)", () => {
    const r = estimatePackRange(false, 70, params)!;
    expect(r.milesLo).toBeCloseTo(896 / 24, 2);
    expect(r.milesHi).toBeCloseTo(896 / 18, 2);
    expect(r.activeHLo).toBeCloseTo(896 / 100, 2);
    expect(r.activeHHi).toBeCloseTo(896 / 70, 2);
    expect(r.wallHLo).toBeCloseTo((896 / 180) * 24, 2);
    expect(r.wallHHi).toBeCloseTo((896 / 100) * 24, 2);
  });
  it("null while charging", () => expect(estimatePackRange(true, 70, params)).toBeNull());
  it("null without remaining capacity", () => {
    expect(estimatePackRange(false, 0, params)).toBeNull();
    expect(estimatePackRange(false, null, params)).toBeNull();
  });
  // SHARED VECTOR: android RangeEstimateTest.kt asserts the same null result for a zero whPerDay band.
  it("null when whPerDay band is zero", () => {
    const zeroDay: RangeParams = { ...params, whPerDay: { lo: 0, hi: 0 } };
    expect(estimatePackRange(false, 70, zeroDay)).toBeNull();
  });
});

describe("minRange", () => {
  it("takes the worst pack per figure", () => {
    const a: PackRange = { milesLo: 37, milesHi: 50, activeHLo: 9, activeHHi: 13, wallHLo: 119, wallHHi: 215 };
    const b: PackRange = { milesLo: 40, milesHi: 45, activeHLo: 8, activeHHi: 14, wallHLo: 125, wallHHi: 200 };
    const m = minRange([a, b]);
    expect([m.milesLo, m.milesHi, m.activeHLo, m.activeHHi, m.wallHLo, m.wallHHi])
      .toEqual([37, 45, 8, 13, 119, 200]);
  });
});

describe("formatRangeLine", () => {
  it("whole miles/hours/days", () => {
    expect(formatRangeLine({ milesLo: 37.33, milesHi: 49.78, activeHLo: 8.96, activeHHi: 12.8, wallHLo: 119.47, wallHHi: 215.04 }))
      .toBe("~37–50 mi · ~9–13h use · ~5–9 days");
  });
  it("decimal miles when low, hours under 48h", () => {
    expect(formatRangeLine({ milesLo: 1.5, milesHi: 2.4, activeHLo: 0.4, activeHHi: 0.6, wallHLo: 34.2, wallHHi: 42.1 }))
      .toBe("~1.5–2.4 mi · ~0–1h use · ~34–42h");
  });
});

describe("selectRangeParams", () => {
  const row = (address: string, ts: number, lo = 100): RangeConfigRow => ({
    device_id: "d", address, wh_per_day_lo: lo, wh_per_day_hi: 180,
    active_w_lo: 70, active_w_hi: 100, wh_per_mile_lo: 18, wh_per_mile_hi: 24,
    learned_days: 10, updated_at_ms: ts,
  });
  it("newest row per address wins", () => {
    const m = selectRangeParams([row("A", 1000, 999), row("A", 2000, 111), row("B", 500)]);
    expect(m.get("A")!.whPerDay.lo).toBe(111);
    expect(m.get("B")!.whPerDay.lo).toBe(100);
  });
});

describe("seeds", () => {
  it("match the spec", () => {
    expect(SEED_RANGE_PARAMS.whPerDay).toEqual({ lo: 78, hi: 182 });
    expect(SEED_RANGE_PARAMS.activeW).toEqual({ lo: 52.5, hi: 97.5 });
    expect(SEED_RANGE_PARAMS.whPerMile).toEqual({ lo: 15, hi: 25 });
  });
});
