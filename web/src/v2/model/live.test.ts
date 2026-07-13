import { describe, expect, it } from "vitest";
import type { FleetItem } from "../../types";
import { LIVE_STALE_MS, isWindowLive, livePosition } from "./live";

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
