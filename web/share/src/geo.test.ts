import { describe, expect, it } from "vitest";
import { arrowRotation, cardinal, fmtDistance, haversineMeters, initialBearingDeg } from "./geo";

describe("geo", () => {
  it("haversine: ~111 km per degree of latitude", () => {
    const m = haversineMeters(43.0, -87.9, 44.0, -87.9);
    expect(m).toBeGreaterThan(110_000);
    expect(m).toBeLessThan(112_500);
  });

  it("bearing: due north/east/south/west", () => {
    expect(Math.round(initialBearingDeg(43, -87.9, 44, -87.9))).toBe(0);
    expect(Math.round(initialBearingDeg(0, 0, 0, 1))).toBe(90);
    expect(Math.round(initialBearingDeg(44, -87.9, 43, -87.9))).toBe(180);
    expect(Math.round(initialBearingDeg(0, 1, 0, 0))).toBe(270);
  });

  it("cardinal names", () => {
    expect(cardinal(0)).toBe("N");
    expect(cardinal(44)).toBe("NE");
    expect(cardinal(90)).toBe("E");
    expect(cardinal(225)).toBe("SW");
    expect(cardinal(359)).toBe("N");
  });

  it("fmtDistance: feet under ~1000ft, miles above", () => {
    expect(fmtDistance(30)).toBe("100 ft");
    expect(fmtDistance(1000)).toBe("0.6 mi");
  });

  it("arrowRotation: relative to heading, null without compass", () => {
    expect(arrowRotation(90, 0)).toBe(90);
    expect(arrowRotation(10, 350)).toBe(20);
    expect(arrowRotation(350, 10)).toBe(340);
    expect(arrowRotation(90, null)).toBeNull();
  });
});
