import { describe, expect, it } from "vitest";
import type { FleetItem } from "../../types";
import type { TrackPoint } from "../track";
import {
  LIVE_STALE_MS, isWindowLive, lastKnownPosition, livePosition,
  lastTrackPosition, resolveChairMarker, type LivePos,
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
  ({ t, lat, lon, power_w: null, current_a: null, soc: null });

describe("lastTrackPosition", () => {
  it("returns the last GPS point of the track (the track head)", () => {
    const pts = [tp(1000, 43.0, -87.9), tp(2000, 43.1, -87.8), tp(3000, 43.2, -87.7)];
    expect(lastTrackPosition(pts)).toEqual({ lat: 43.2, lon: -87.7, tsMs: 3000 });
  });
  it("null for an empty track", () => {
    expect(lastTrackPosition([])).toBeNull();
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
