import { describe, it, expect } from "vitest";
import {
  REDODO_DEFAULTS, tempZone, tempFillPct, cToF, formatTemp, formatDelta,
  marginToCutoffC, worstOf,
} from "./temp";

const T = REDODO_DEFAULTS;

describe("tempZone ladder", () => {
  it("cold side", () => {
    expect(tempZone(-25, T)).toMatchObject({ rank: 4, side: "cold", key: "cutoffCold" });
    expect(tempZone(-20, T)).toMatchObject({ rank: 4, side: "cold" });
    expect(tempZone(-12, T)).toMatchObject({ rank: 3, side: "cold", key: "critCold" }); // at threshold
    expect(tempZone(-5, T)).toMatchObject({ rank: 2, side: "cold", key: "warnCold" });  // charge lock
    expect(tempZone(0, T)).toMatchObject({ rank: 2, side: "cold" });                    // 0C lock edge
    expect(tempZone(5, T)).toMatchObject({ rank: 1, side: "cold", key: "cautionCold" });
  });
  it("safe", () => {
    expect(tempZone(24, T)).toMatchObject({ rank: 0, side: "safe", key: "safe" });
  });
  it("hot side", () => {
    expect(tempZone(45, T)).toMatchObject({ rank: 1, side: "hot", key: "cautionHot" });
    expect(tempZone(50, T)).toMatchObject({ rank: 2, side: "hot", key: "warnHot" });
    expect(tempZone(53, T)).toMatchObject({ rank: 3, side: "hot", key: "critHot" });
    expect(tempZone(60, T)).toMatchObject({ rank: 4, side: "hot", key: "cutoffHot" });
  });
});

describe("gauge + conversions", () => {
  it("fill maps -30..70 to 0..100 clamped", () => {
    expect(tempFillPct(-30)).toBe(0);
    expect(tempFillPct(0)).toBe(30);
    expect(tempFillPct(70)).toBe(100);
    expect(tempFillPct(-99)).toBe(0);
    expect(tempFillPct(99)).toBe(100);
  });
  it("cToF", () => {
    expect(cToF(0)).toBe(32);
    expect(cToF(-20)).toBe(-4);
    expect(cToF(-12)).toBe(10);
    expect(cToF(60)).toBe(140);
  });
  it("formatTemp", () => {
    expect(formatTemp(-12, "F")).toBe("10°F");
    expect(formatTemp(-12, "C")).toBe("-12°C");
  });
  it("formatDelta (difference, no offset)", () => {
    expect(formatDelta(8, "F")).toBe("14°F");
    expect(formatDelta(8, "C")).toBe("8°C");
  });
});

describe("margin + worst", () => {
  it("margin to cutoff", () => {
    expect(marginToCutoffC(-12, "cold")).toBe(8);   // -12 - (-20)
    expect(marginToCutoffC(55, "hot")).toBe(5);     // 60 - 55
  });
  it("worst pack wins by rank", () => {
    const a = { rank: 1 as const }, b = { rank: 3 as const }, c = { rank: 0 as const };
    expect(worstOf([a, b, c])).toBe(b);
    expect(worstOf([])).toBeUndefined();
  });
});
