import { describe, expect, it } from "vitest";
import { KM_PER_MI, distLabel, fmtDist, fromDist, perDist, toDist, type DistUnit } from "./units";

describe("units", () => {
  it("converts miles to the display unit and back", () => {
    expect(toDist(10, "mi")).toBe(10);
    expect(toDist(10, "km")).toBeCloseTo(16.09344, 9);
    expect(fromDist(toDist(7.25, "km"), "km")).toBeCloseTo(7.25, 9);
    expect(fromDist(5, "mi")).toBe(5);
  });

  it("formats with the unit label, and shows anything unknown as miles", () => {
    expect(fmtDist(3.21, "mi")).toBe("3.2 mi");
    expect(fmtDist(1, "km")).toBe("1.6 km");
    expect(fmtDist(0.5, "km", 2)).toBe("0.80 km");
    expect(distLabel("furlongs" as DistUnit)).toBe("mi");
    expect(fmtDist(2, "furlongs" as DistUnit)).toBe("2.0 mi");
  });

  it("divides a per-mile cost into a per-km cost", () => {
    expect(perDist(80, "mi")).toBe(80);
    expect(perDist(80, "km")).toBeCloseTo(80 / KM_PER_MI, 9);
  });
});
