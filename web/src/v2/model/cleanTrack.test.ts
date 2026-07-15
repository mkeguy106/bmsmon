import { describe, expect, it } from "vitest";
import type { TrackPoint } from "../track";
import { haversineMi } from "./journey";
import { cleanTrack, collapseIdleExcursions, rejectSpikes, smoothTrack, snapStays } from "./cleanTrack";

// ~degrees of latitude per meter (1 deg lat = 111 320 m).
const M = 1 / 111_320;

/** Point [m] meters north of origin at time [tS] seconds, drawing [amps] (negative = discharge). */
const pt = (tS: number, m: number, amps = -2): TrackPoint => ({
  t: tS * 1000, lat: 40 + m * M, lon: -75, power_w: Math.abs(amps) * 25, current_a: amps, soc: 80,
});

const totalMi = (ps: TrackPoint[]) =>
  ps.reduce((acc, p, i) => (i ? acc + haversineMi(ps[i - 1], p) : 0), 0);

describe("rejectSpikes", () => {
  it("drops a discharging out-and-back spike (impossible at chair speed)", () => {
    // A at 0 m, B jumps 200 m in 15 s (13.3 m/s in AND out), C back at 10 m.
    const track = [pt(0, 0), pt(15, 200), pt(30, 10)];
    const out = rejectSpikes(track);
    expect(out).toHaveLength(2);
    expect(out[1].t).toBe(30_000);
  });

  it("keeps a sustained fast leg while NOT discharging (vehicle ride)", () => {
    // 300 m per 15 s = 20 m/s, current 0 — a real van; every point kept.
    const track = [pt(0, 0, 0), pt(15, 300, 0), pt(30, 600, 0), pt(45, 900, 0)];
    expect(rejectSpikes(track)).toHaveLength(4);
  });

  it("keeps a sustained fast leg even while discharging (GPS reacquire, not a spike)", () => {
    // B is fast IN but the track CONTINUES from B (B→C plausible) — not out-and-back; kept.
    const track = [pt(0, 0), pt(15, 200), pt(30, 210), pt(45, 220)];
    expect(rejectSpikes(track)).toHaveLength(4);
  });

  it("drops absurd jumps regardless of discharge state", () => {
    // 1200 m in 15 s = 80 m/s — impossible even for a vehicle.
    const track = [pt(0, 0, 0), pt(15, 1200, 0), pt(30, 10, 0)];
    expect(rejectSpikes(track)).toHaveLength(2);
  });
});

describe("snapStays", () => {
  it("snaps parked jitter to one spot without removing points", () => {
    // 6 points wobbling within ±10 m, then a real move to 200 m.
    const track = [pt(0, 0), pt(15, 8), pt(30, -6), pt(45, 10), pt(60, 2), pt(75, -9), pt(90, 200)];
    const out = snapStays(track);
    expect(out).toHaveLength(7);                      // timeline preserved
    const cluster = out.slice(0, 6);
    expect(new Set(cluster.map((p) => p.lat)).size).toBe(1);  // all snapped to one coord
    expect(totalMi(cluster)).toBe(0);                 // parked distance is zero
    expect(out[6].lat).toBe(track[6].lat);            // the move survives
  });
});

describe("collapseIdleExcursions", () => {
  // Real incident 2026-07-14: with the chair parked at the condo (idle), elevator multipath
  // produced fixes claiming 9-32 m accuracy that zigzagged 110-140 m east over the railroad
  // tracks and back over ~2 min. Too slow for spike rejection, too far for stay snapping.

  it("collapses an idle out-and-back excursion onto the anchor (elevator multipath)", () => {
    const track = [
      pt(0, 0, 0), pt(15, 5, 0), pt(30, 120, 0), pt(45, 130, 0), pt(150, 115, 0),
      pt(165, 3, 0), pt(180, -4, 0),
    ];
    const out = collapseIdleExcursions(track);
    expect(out).toHaveLength(7);                       // timeline preserved
    expect(totalMi(out.slice(0, 6))).toBeLessThan(0.01);
    expect(out[2].lat).toBe(out[1].lat);               // snapped to the anchor
    expect(out[2].t).toBe(30_000);                     // timestamps untouched
  });

  it("collapses a single-fix idle teleport (the 100 m Wi-Fi clamp fix)", () => {
    const track = [pt(0, 0, 0), pt(15, 2, 0), pt(30, 149, 0), pt(45, 1, 0)];
    const out = collapseIdleExcursions(track);
    expect(out[2].lat).toBe(out[1].lat);
    expect(totalMi(out)).toBeLessThan(0.01);
  });

  it("collapses an excursion with a straggler on the way back (parked wobble)", () => {
    // Real 18:52 UTC case: two idle fixes 50-66 m west (over the tracks), a 35 m straggler
    // on the way back, then re-anchored. The straggler is part of the excursion.
    const track = [pt(0, 0, 0), pt(15, 5, 0), pt(30, -50, 0), pt(45, -61, 0), pt(60, 40, 0), pt(75, 2, 0)];
    const out = collapseIdleExcursions(track);
    expect(out[2].lat).toBe(out[1].lat);
    expect(out[3].lat).toBe(out[1].lat);
    expect(out[4].lat).toBe(out[1].lat);
    expect(totalMi(out)).toBeLessThan(0.01);
  });

  it("keeps a discharging out-and-back loop (a real quick chair errand)", () => {
    const track = [
      pt(0, 0, 0), pt(15, 5, 0), pt(30, 120), pt(45, 130), pt(150, 115),
      pt(165, 3, 0), pt(180, -4, 0),
    ];
    const out = collapseIdleExcursions(track);
    expect(out[2].lat).toBe(track[2].lat);
    expect(out[3].lat).toBe(track[3].lat);
  });

  it("keeps an idle departure that never returns (vehicle ride leaving)", () => {
    const track = [pt(0, 0, 0), pt(15, 5, 0), pt(30, 300, 0), pt(45, 600, 0), pt(60, 900, 0)];
    const out = collapseIdleExcursions(track);
    expect(out.map((p) => p.lat)).toEqual(track.map((p) => p.lat));
  });

  it("keeps a long idle excursion (> 5 min away is not elevator jitter)", () => {
    const track = [
      pt(0, 0, 0), pt(15, 5, 0), pt(30, 120, 0), pt(200, 130, 0), pt(400, 125, 0),
      pt(430, 118, 0), pt(445, 3, 0),
    ];
    const out = collapseIdleExcursions(track);
    expect(out[2].lat).toBe(track[2].lat);
    expect(out[4].lat).toBe(track[4].lat);
  });
});

describe("smoothTrack", () => {
  it("preserves endpoints and point count", () => {
    const track = [pt(0, 0), pt(15, 30), pt(30, 55), pt(45, 90)];
    const out = smoothTrack(track);
    expect(out).toHaveLength(4);
    expect(out[0].lat).toBe(track[0].lat);
    expect(out[3].lat).toBe(track[3].lat);
  });
});

describe("cleanTrack", () => {
  it("preserves a genuine chair drive's distance within a few percent", () => {
    // Steady 2 m/s: 30 m per 15 s, 40 points → 39 segments × 30 m = 1170 m ≈ 0.727 mi.
    const track = Array.from({ length: 40 }, (_, i) => pt(i * 15, i * 30));
    const out = cleanTrack(track);
    expect(totalMi(out)).toBeGreaterThan(0.727 * 0.97);
    expect(totalMi(out)).toBeLessThan(0.727 * 1.01);
  });

  it("flattens repeated idle elevator zigzags to zero distance end-to-end", () => {
    const parked = (tS: number) => pt(tS, ((tS * 7) % 13) - 6, 0);   // ±6 m jitter, idle
    const track = [
      parked(0), parked(15), parked(30),
      pt(45, 120, 0), pt(60, 135, 0), pt(165, 110, 0),   // zigzag 1
      parked(180), parked(195),
      pt(210, 140, 0),                                   // zigzag 2 (single fix)
      parked(225), parked(240), parked(255),
    ];
    expect(totalMi(cleanTrack(track))).toBeLessThan(0.01);
  });

  it("removes the spike's phantom distance from the total", () => {
    // Same drive with one 400 m out-and-back spike injected mid-way (~0.5 phantom mi raw).
    const drive = Array.from({ length: 40 }, (_, i) => pt(i * 15, i * 30));
    const spiked = [...drive.slice(0, 20), pt(20 * 15 - 7, 400 + 20 * 30), ...drive.slice(20)];
    const out = cleanTrack(spiked);
    expect(totalMi(out)).toBeLessThan(0.727 * 1.05);
  });
});
