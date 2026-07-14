import { describe, expect, it } from "vitest";
import type { TrackPoint } from "../track";
import { appendTrack } from "./appendTrack";

const pt = (t: number, v = 1): TrackPoint =>
  ({ t, lat: 40 + v / 1000, lon: -75 - v / 1000, power_w: v * 10, current_a: -v, soc: 90 - v });

describe("appendTrack", () => {
  it("empty prev: returns the incoming points", () => {
    const inc = [pt(0), pt(15_000)];
    expect(appendTrack([], inc, 0)).toEqual(inc);
  });

  it("empty prev + empty incoming: returns prev identity", () => {
    const prev: TrackPoint[] = [];
    expect(appendTrack(prev, [], 0)).toBe(prev);
  });

  it("pure append: seam bucket unchanged, new buckets follow", () => {
    const prev = [pt(0), pt(15_000), pt(30_000, 3)];
    const inc = [pt(30_000, 3), pt(45_000, 4)];
    const out = appendTrack(prev, inc, 30_000);
    expect(out).toEqual([pt(0), pt(15_000), pt(30_000, 3), pt(45_000, 4)]);
  });

  it("seam-bucket replacement: the partially-filled seam bucket's averages changed", () => {
    const prev = [pt(0), pt(15_000, 2)];
    const inc = [pt(15_000, 5), pt(30_000, 6)]; // same t, new values
    const out = appendTrack(prev, inc, 15_000);
    expect(out).toEqual([pt(0), pt(15_000, 5), pt(30_000, 6)]);
  });

  it("no change: same seam bucket t and values → returns the PREVIOUS array identity", () => {
    const prev = [pt(0), pt(15_000, 2)];
    const inc = [pt(15_000, 2)]; // byte-identical seam bucket, nothing new
    expect(appendTrack(prev, inc, 15_000)).toBe(prev);
  });

  it("incoming overlapping more than the seam: cuts prev at the incoming's first t", () => {
    const prev = [pt(0), pt(15_000, 2), pt(30_000, 3)];
    const inc = [pt(15_000, 9), pt(30_000, 9), pt(45_000, 9)]; // starts BEFORE seamT
    const out = appendTrack(prev, inc, 30_000);
    expect(out).toEqual([pt(0), pt(15_000, 9), pt(30_000, 9), pt(45_000, 9)]);
  });

  it("empty incoming never truncates: returns prev identity (no news, keep the trail)", () => {
    const prev = [pt(0), pt(15_000, 2)];
    expect(appendTrack(prev, [], 15_000)).toBe(prev);
  });

  it("full replace (seamT = -Infinity) with identical data keeps prev identity", () => {
    const prev = [pt(0), pt(15_000, 2)];
    const inc = [pt(0), pt(15_000, 2)];
    expect(appendTrack(prev, inc, Number.NEGATIVE_INFINITY)).toBe(prev);
  });

  it("full replace (seamT = -Infinity) with changed data replaces everything", () => {
    const prev = [pt(0), pt(15_000, 2)];
    const inc = [pt(0, 7), pt(15_000, 8), pt(30_000, 9)];
    const out = appendTrack(prev, inc, Number.NEGATIVE_INFINITY);
    expect(out).toEqual(inc);
    expect(out).not.toBe(prev);
  });

  it("value-only field differences are detected (null vs number)", () => {
    const a: TrackPoint = { t: 0, lat: 1, lon: 2, power_w: null, current_a: null, soc: null };
    const b: TrackPoint = { ...a, power_w: 0 };
    expect(appendTrack([a], [b], 0)).toEqual([b]);
    const prev = [a];
    expect(appendTrack(prev, [{ ...a }], 0)).toBe(prev); // equal fields → prev identity
  });
});
