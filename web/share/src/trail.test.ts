import { describe, expect, it } from "vitest";
import type { TrackPoint } from "../../src/v2/track";
import { loadTrailMode, saveTrailMode, trailProps } from "./trail";

// ~0.005 mi apart (> MOVE_EPS_MI) so movement-based transit classification triggers.
const pt = (i: number, current: number | null, power: number | null): TrackPoint => ({
  t: i * 15_000, lat: 43.0 + i * 0.0001, lon: -87.9,
  power_w: power, current_a: current, soc: null,
});

const fakeStorage = (init: Record<string, string> = {}) => {
  const m = new Map(Object.entries(init));
  return {
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => void m.set(k, v),
  };
};

describe("trailProps", () => {
  const points = [pt(0, -3, -40), pt(1, -3, -40), pt(2, 0, 0)];

  it("detail: classifies discharge vs transit and keeps power", () => {
    const r = trailProps(points, "detail");
    expect(r.points).toBe(points); // untouched passthrough
    expect(r.segKinds[1]).toBe("active");   // discharging while moving
    expect(r.segKinds[2]).toBe("transit");  // moving, no discharge → vehicle
    expect(r.segKinds).toHaveLength(3);
  });

  it("plain: strips discharge context and flattens kinds", () => {
    const r = trailProps(points, "plain");
    expect(r.segKinds).toEqual(["active", "active", "active"]);
    expect(r.points[0].power_w).toBeNull();
    expect(r.points[0].current_a).toBeNull();
    expect(r.points[0].lat).toBe(points[0].lat);
  });

  it("empty input", () => {
    expect(trailProps([], "detail")).toEqual({ points: [], segKinds: [] });
  });
});

describe("trail mode persistence", () => {
  it("defaults to detail; junk is detail", () => {
    expect(loadTrailMode(fakeStorage())).toBe("detail");
    expect(loadTrailMode(fakeStorage({ "bmsmon-share-trail": "nope" }))).toBe("detail");
  });

  it("round-trips plain and survives throwing storage", () => {
    const s = fakeStorage();
    saveTrailMode(s, "plain");
    expect(loadTrailMode(s)).toBe("plain");
    const throwing = {
      getItem: () => { throw new Error("nope"); },
      setItem: () => { throw new Error("nope"); },
    };
    expect(loadTrailMode(throwing)).toBe("detail");
    expect(() => saveTrailMode(throwing, "plain")).not.toThrow();
  });
});
