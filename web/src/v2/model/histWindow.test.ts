import { describe, expect, it } from "vitest";
import { trendQuantumMs, quantizeMs, type HistRange } from "./histWindow";

describe("trendQuantumMs", () => {
  it("keeps the 24H window on a 60 s quantum", () => {
    expect(trendQuantumMs("24h")).toBe(60_000);
  });

  it("uses a 10 min quantum for every longer window", () => {
    const longRanges: HistRange[] = ["7d", "1m", "1y", "all", "custom"];
    for (const r of longRanges) expect(trendQuantumMs(r)).toBe(600_000);
  });
});

describe("quantizeMs", () => {
  it("is stable within a quantum", () => {
    const q = 600_000;
    const base = 1_752_500_400_000; // exactly on a 10-min boundary
    expect(quantizeMs(base, q)).toBe(base);
    expect(quantizeMs(base + 1, q)).toBe(base);
    expect(quantizeMs(base + q - 1, q)).toBe(base);
  });

  it("advances across the quantum boundary", () => {
    const q = 600_000;
    const base = 1_752_500_400_000;
    expect(quantizeMs(base + q, q)).toBe(base + q);
    expect(quantizeMs(base + q + 1, q)).toBe(base + q);
  });

  it("works for the 60 s quantum too", () => {
    const q = 60_000;
    const t = 1_752_500_431_234;
    const floored = Math.floor(t / q) * q;
    expect(quantizeMs(t, q)).toBe(floored);
    expect(quantizeMs(floored + 59_999, q)).toBe(floored);
    expect(quantizeMs(floored + 60_000, q)).toBe(floored + 60_000);
  });
});
