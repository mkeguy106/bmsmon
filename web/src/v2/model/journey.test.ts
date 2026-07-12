import { describe, expect, it } from "vitest";
import {
  haversineMi, classifySegment, dischargeColor, cumulativeMiles,
  detectHotspots, energySeries, tripSummary, mergeBaseTracks,
} from "./journey";
import type { TrackPoint, Track } from "../track";

const p = (o: Partial<TrackPoint>): TrackPoint =>
  ({ t: 0, lat: 43, lon: -87.9, power_w: 0, current_a: 0, soc: 88, ...o });

describe("haversineMi", () => {
  it("~69 mi per degree of longitude at the equator", () => {
    expect(haversineMi({ lat: 0, lon: 0 }, { lat: 0, lon: 1 })).toBeCloseTo(69.09, 1);
  });
  it("zero for identical points", () => {
    expect(haversineMi({ lat: 43, lon: -87 }, { lat: 43, lon: -87 })).toBe(0);
  });
});

describe("classifySegment", () => {
  it("active when discharging", () => expect(classifySegment(p({ current_a: -4 }), 0.01)).toBe("active"));
  it("transit when idle and moved", () => expect(classifySegment(p({ current_a: 0 }), 0.05)).toBe("transit"));
  it("idle when stationary", () => expect(classifySegment(p({ current_a: 0 }), 0)).toBe("idle"));
});

describe("dischargeColor", () => {
  it("green/amber/red by |power|", () => {
    expect(dischargeColor(-50)).toBe("var(--ok)");
    expect(dischargeColor(-200)).toBe("var(--warn)");
    expect(dischargeColor(-400)).toBe("var(--live)");
  });
});

describe("cumulativeMiles + tripSummary", () => {
  it("accumulates distance and sums active miles", () => {
    const pts = [p({ lon: -87.9, current_a: -4 }), p({ lon: -87.89, current_a: -4 })];
    const cum = cumulativeMiles(pts);
    expect(cum[0]).toBe(0);
    expect(cum[1]).toBeGreaterThan(0);
    const s = tripSummary(pts, cum);
    expect(s.activeMiles).toBeCloseTo(cum[1], 5);
    expect(s.peakW).toBe(0); // power_w is 0 here
  });

  it("peakW considers the first point, not just the pairwise loop from index 1", () => {
    const pts = [p({ power_w: -500, current_a: -4 }), p({ power_w: -50, current_a: -4, lon: -87.89 })];
    const cum = cumulativeMiles(pts);
    const s = tripSummary(pts, cum);
    expect(s.peakW).toBe(500);
  });

  it("peakW handles a single-point trip", () => {
    const pts = [p({ power_w: -300 })];
    const cum = cumulativeMiles(pts);
    const s = tripSummary(pts, cum);
    expect(s.peakW).toBe(300);
  });
});

describe("detectHotspots", () => {
  it("finds an interior |power| maximum above threshold", () => {
    const pts = [p({ power_w: -100 }), p({ power_w: -400, lon: -87.8 }), p({ power_w: -100, lon: -87.7 })];
    const cum = cumulativeMiles(pts);
    const hs = detectHotspots(pts, cum, { thresholdW: 330, minGapMi: 0.1 });
    expect(hs.map((h) => h.index)).toEqual([1]);
  });
});

describe("mergeBaseTracks", () => {
  it("sums power/current across packs at aligned t, means coords, min soc", () => {
    const a: Track = { address: "A", points: [p({ t: 100, power_w: -30, current_a: -2, soc: 90 })] };
    const b: Track = { address: "B", points: [p({ t: 100, power_w: -40, current_a: -3, soc: 85 })] };
    const merged = mergeBaseTracks([a, b]);
    expect(merged).toHaveLength(1);
    expect(merged[0].power_w).toBe(-70);
    expect(merged[0].current_a).toBe(-5);
    expect(merged[0].soc).toBe(85);
  });
});

describe("energySeries", () => {
  it("flags transit legs", () => {
    const pts = [p({ current_a: 0, lon: -87.9 }), p({ current_a: 0, lon: -87.8 })];
    const es = energySeries(pts, cumulativeMiles(pts));
    expect(es[1].transit).toBe(true);
  });
});
