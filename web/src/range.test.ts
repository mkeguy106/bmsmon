import { describe, expect, it } from "vitest";
import {
  SEED_RANGE_PARAMS, estimatePackRange, floorBand, floorFixed, formatRangeLine, minRange,
  selectRangeParams, type PackRange, type RangeConfigRow, type RangeParams,
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

// Every figure is FLOORED: a range, runtime or mileage is never shown above its estimate.
// Rounding to nearest showed 49.78 mi as "50" and 0.6 h of use as "1h" (67% high near empty).
describe("formatRangeLine", () => {
  it("whole miles/hours/days, floored", () => {
    expect(formatRangeLine({ milesLo: 37.33, milesHi: 49.78, activeHLo: 8.96, activeHHi: 12.8, wallHLo: 119.47, wallHHi: 215.04 }))
      .toBe("~37–49 mi · ~8–12h use · ~4–8 days");
  });
  it("decimal miles when low, decimal hours under an hour, hours under 48h", () => {
    expect(formatRangeLine({ milesLo: 1.5, milesHi: 2.4, activeHLo: 0.4, activeHHi: 0.6, wallHLo: 34.2, wallHHi: 42.1 }))
      .toBe("~1.5–2.4 mi · ~0.4–0.6h use · ~34–42h");
  });
  it("converts only the distance to km", () => {
    expect(formatRangeLine({ milesLo: 37.33, milesHi: 49.78, activeHLo: 8.96, activeHHi: 12.8, wallHLo: 119.47, wallHHi: 215.04 }, "km"))
      .toBe("~60–80 km · ~8–12h use · ~4–8 days");
  });
  it("never rounds a figure up, at either end or across the decimal switch", () => {
    expect(formatRangeLine({ milesLo: 30.9, milesHi: 37.6, activeHLo: 1.99, activeHHi: 2.99, wallHLo: 47.9, wallHHi: 47.99 }))
      .toBe("~30–37 mi · ~1–2h use · ~47–47h");
    expect(formatRangeLine({ milesLo: 8.99, milesHi: 9.96, activeHLo: 0.99, activeHHi: 0.999, wallHLo: 100, wallHHi: 167.9 }))
      .toBe("~8.9–9.9 mi · ~0.9–0.9h use · ~4–6 days");
  });
});

describe("floorBand / floorFixed", () => {
  it("floors to the shown precision", () => {
    expect(floorFixed(37.6)).toBe("37");
    expect(floorFixed(9.96, 1)).toBe("9.9");
    expect(floorFixed(0, 1)).toBe("0.0");
    expect(floorBand(1.55, 2.47, 10)).toBe("1.5–2.4");
    expect(floorBand(12.9, 19.99, 10)).toBe("12–19");
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
    expect(SEED_RANGE_PARAMS.whPerMile).toEqual({ lo: 51, hi: 85 });
  });
});
