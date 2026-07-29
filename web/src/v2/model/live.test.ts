import { describe, expect, it } from "vitest";
import type { FleetItem } from "../../types";
import type { TrackPoint } from "../track";
import {
  LIVE_STALE_MS, isWindowLive, lastKnownPosition, livePosition,
  lastTrackPosition, resolveChairMarker, predictPosition, PREDICT_MAX_MS, PREDICT_MAX_M,
  type LivePos,
} from "./live";

const item = (address: string, ts_ms: number, lat: number | null): FleetItem =>
  ({ address, ts_ms, lat, lon: lat == null ? null : -75 } as FleetItem);

describe("isWindowLive", () => {
  it("true when the window contains now", () => {
    expect(isWindowLive(500, 1500, 1000)).toBe(true);
  });
  it("false when now equals the window end (toMs)", () => {
    expect(isWindowLive(500, 1000, 1000)).toBe(false);
  });
  it("false for a fully future window", () => {
    expect(isWindowLive(1001, 2000, 1000)).toBe(false);
  });
  it("false for a fully past window", () => {
    expect(isWindowLive(0, 500, 1000)).toBe(false);
  });
});

describe("livePosition", () => {
  const now = 1_000_000;
  it("returns the freshest GPS fix among the base's packs", () => {
    const items = [
      item("A", now - 10_000, 40.1),
      item("B", now - 5_000, 40.2),
      item("X", now - 1_000, 40.9),   // not in the base — ignored
    ];
    const p = livePosition(items, ["A", "B"], now);
    expect(p).toEqual({ lat: 40.2, lon: -75, tsMs: now - 5_000 });
  });
  it("null when the freshest fix is stale", () => {
    const items = [item("A", now - LIVE_STALE_MS - 1, 40.1)];
    expect(livePosition(items, ["A"], now)).toBeNull();
  });
  it("exactly at the cutoff still shows", () => {
    const items = [item("A", now - LIVE_STALE_MS, 40.1)];
    expect(livePosition(items, ["A"], now)).not.toBeNull();
  });
  it("skips items without GPS", () => {
    const items = [item("A", now - 1_000, null), item("B", now - 9_000, 40.3)];
    expect(livePosition(items, ["A", "B"], now)?.lat).toBe(40.3);
  });
  it("null for empty addresses or no matches", () => {
    expect(livePosition([item("A", now, 40)], [], now)).toBeNull();
    expect(livePosition([], ["A"], now)).toBeNull();
  });
});

describe("lastKnownPosition", () => {
  const now = 1_000_000;
  it("returns the freshest GPS fix regardless of age", () => {
    const items = [
      item("A", now - LIVE_STALE_MS * 10, 40.1),
      item("B", now - LIVE_STALE_MS * 3, 40.2),
    ];
    const p = lastKnownPosition(items, ["A", "B"]);
    expect(p).toEqual({ lat: 40.2, lon: -75, tsMs: now - LIVE_STALE_MS * 3 });
  });
  it("null when no pack has a fix or addresses empty", () => {
    expect(lastKnownPosition([item("A", now, null)], ["A"])).toBeNull();
    expect(lastKnownPosition([item("A", now, 40)], [])).toBeNull();
  });
});

const tp = (t: number, lat: number, lon: number): TrackPoint =>
  ({ t, lat, lon, power_w: null, current_a: null, soc: null, acc: null });

describe("lastTrackPosition", () => {
  it("returns the last GPS point of the track (the track head)", () => {
    const pts = [tp(1000, 43.0, -87.9), tp(2000, 43.1, -87.8), tp(3000, 43.2, -87.7)];
    expect(lastTrackPosition(pts)).toEqual({ lat: 43.2, lon: -87.7, tsMs: 3000 });
  });
  it("null for an empty track", () => {
    expect(lastTrackPosition([])).toBeNull();
  });
});

const tpi = (t: number, lat: number, lon: number, inferred?: boolean) =>
  ({ t, lat, lon, power_w: null, current_a: null, soc: null, acc: 10, ...(inferred ? { inferred } : {}) });

describe("predictPosition", () => {
  const mLon = 111_320 * Math.cos((43 * Math.PI) / 180);
  // Heading east at 10 m/s, last fix at t=15000.
  const pts = [tpi(0, 43, -87.9), tpi(15_000, 43, -87.9 + 150 / mLon)];

  it("extrapolates along the last known velocity", () => {
    const p = predictPosition(pts, 20_000)!;           // 5 s past the last fix → ~50 m further
    expect((p.lon - -87.9) * mLon).toBeCloseTo(200, 0);
  });

  it("caps extrapolation by time", () => {
    const far = predictPosition(pts, 15_000 + PREDICT_MAX_MS + 60_000)!;
    const atCap = predictPosition(pts, 15_000 + PREDICT_MAX_MS)!;
    expect(far.lat).toBeCloseTo(atCap.lat, 10);
    expect(far.lon).toBeCloseTo(atCap.lon, 10);
  });

  it("caps extrapolation by distance", () => {
    // 40 m/s (train): the 200 m cap binds before the 10 s one.
    const fast = [tpi(0, 43, -87.9), tpi(15_000, 43, -87.9 + 600 / mLon)];
    const p = predictPosition(fast, 15_000 + PREDICT_MAX_MS)!;
    expect((p.lon - -87.9) * mLon - 600).toBeLessThanOrEqual(PREDICT_MAX_M + 0.5);
  });

  it("never predicts backwards in time", () => {
    const p = predictPosition(pts, 10_000)!;
    expect(p.lon).toBeCloseTo(-87.9 + 150 / mLon, 10);
  });

  it("does not extrapolate across an inferred segment", () => {
    const broken = [tpi(0, 43, -87.9), tpi(200_000, 43, -87.9 + 3000 / mLon, true)];
    const p = predictPosition(broken, 205_000)!;
    expect(p.lon).toBeCloseTo(-87.9 + 3000 / mLon, 10);   // holds the last fix
  });

  it("returns null without at least two points", () => {
    expect(predictPosition([], 1000)).toBeNull();
    expect(predictPosition([tpi(0, 43, -87.9)], 1000)).not.toBeNull();  // single fix: hold it
  });
});

describe("resolveChairMarker", () => {
  const now = 1_000_000;
  const pos = (tsMs: number, lat = 40): LivePos => ({ lat, lon: -75, tsMs });

  it("picks the newest candidate by timestamp", () => {
    const r = resolveChairMarker([pos(now - 50_000, 1), pos(now - 8_000, 2), null], now);
    expect(r.pos?.lat).toBe(2);
    expect(r.stale).toBe(false);
  });
  it("shows the last-known position greyed when the newest fix is stale (never null)", () => {
    // The real bug: the fleet snapshot's latest row lacks GPS, but a 8-min-old fix exists.
    const r = resolveChairMarker([null, pos(now - LIVE_STALE_MS * 4)], now);
    expect(r.pos).not.toBeNull();
    expect(r.stale).toBe(true);
  });
  it("is not stale exactly at the cutoff", () => {
    const r = resolveChairMarker([pos(now - LIVE_STALE_MS)], now);
    expect(r.stale).toBe(false);
  });
  it("null pos only when every candidate is null", () => {
    const r = resolveChairMarker([null, null], now);
    expect(r).toEqual({ pos: null, stale: false });
  });
});
