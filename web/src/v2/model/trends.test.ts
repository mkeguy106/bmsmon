import { describe, expect, it } from "vitest";
import { projectMonthsTo80, sohBand } from "./trends";

const DAY = 86_400_000;
const line = (n: number, soh0: number, perDay: number) =>
  Array.from({ length: n }, (_, i) => ({ t: i * DAY, soh: soh0 + perDay * i }));

describe("projectMonthsTo80", () => {
  it("projects months for a steady decline", () => {
    const m = projectMonthsTo80(line(20, 100, -0.5)); // ~ -0.5 soh/day from ~90 at last point
    expect(m).not.toBeNull();
    expect(m!).toBeGreaterThan(0);
  });
  it("null for a flat/rising trend", () => {
    expect(projectMonthsTo80(line(20, 95, 0))).toBeNull();
    expect(projectMonthsTo80(line(20, 90, 0.2))).toBeNull();
  });
  it("null with too few points", () => {
    expect(projectMonthsTo80(line(3, 100, -1))).toBeNull();
  });
});

describe("sohBand", () => {
  it("bands", () => {
    expect(sohBand(95)).toBe("good");
    expect(sohBand(85)).toBe("fair");
    expect(sohBand(70)).toBe("degraded");
  });
});
