import { describe, expect, it } from "vitest";
import { isStale, remainingLabel, tokenFromPath } from "./feed";

describe("feed model", () => {
  it("tokenFromPath accepts /share/<token> only", () => {
    expect(tokenFromPath("/share/AbC123xyz_-AbC123xyz_-AbC123xyz")).toBe(
      "AbC123xyz_-AbC123xyz_-AbC123xyz");
    expect(tokenFromPath("/share/AbC123xyz_-AbC123xyz_-AbC123xyz/")).toBe(
      "AbC123xyz_-AbC123xyz_-AbC123xyz");
    expect(tokenFromPath("/share/")).toBeNull();
    expect(tokenFromPath("/share/short")).toBeNull();
    expect(tokenFromPath("/share/index.html")).toBeNull();
    expect(tokenFromPath("/v2/")).toBeNull();
  });

  it("isStale after 120s or with no fix", () => {
    expect(isStale(null, 1_000_000)).toBe(true);
    expect(isStale({ t: 1_000_000 - 119_000, lat: 0, lon: 0, power_w: null, current_a: null }, 1_000_000)).toBe(false);
    expect(isStale({ t: 1_000_000 - 121_000, lat: 0, lon: 0, power_w: null, current_a: null }, 1_000_000)).toBe(true);
  });

  it("remainingLabel formats h/m/d", () => {
    expect(remainingLabel(1_000_000, 1_000_001)).toBe("expired");
    expect(remainingLabel(90_000 + 0, 0)).toBe("1m left");
    expect(remainingLabel(2 * 3_600_000 + 5 * 60_000, 0)).toBe("2h 5m left");
    expect(remainingLabel(3 * 86_400_000, 0)).toBe("3d left");
  });
});
