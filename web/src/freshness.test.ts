import { describe, expect, it } from "vitest";
import { createStore } from "./store";
import { STALE_MS, isPackStale, staleAddresses } from "./freshness";
import type { FleetItem } from "./types";

const T = 1_000_000;
const pack = (o: Partial<FleetItem> = {}): FleetItem => ({ address: "A", ts_ms: T, soc: 60, ...o });

describe("isPackStale", () => {
  it("is fresh at exactly STALE_MS and stale one millisecond later", () => {
    expect(isPackStale(pack(), T + STALE_MS)).toBe(false);
    expect(isPackStale(pack(), T + STALE_MS + 1)).toBe(true);
  });

  it("reads a Disconnected link event newer than the telemetry as stale at once (WEB-22)", () => {
    expect(isPackStale(pack({ link_event: "Disconnected", link_ts_ms: T + 5_000 }), T + 6_000)).toBe(true);
  });

  it("does not let a Connected link event make an old reading fresh", () => {
    expect(isPackStale(pack({ link_event: "Connected", link_ts_ms: T + 100_000 }), T + 100_001)).toBe(true);
  });
});

describe("staleAddresses", () => {
  it("collects the stale packs", () => {
    const items = [pack({ address: "A" }), pack({ address: "B", ts_ms: T - STALE_MS - 1 })];
    expect(staleAddresses(items, T)).toEqual(new Set(["B"]));
  });

  // WEB-22: Android emits a link event on every reachability transition, and each one used to
  // refresh ts_ms, so a pack that only flapped its link never went stale.
  it("lets a pack whose link only flaps go stale (store + freshness)", () => {
    const s = createStore();
    s.applySample({ address: "A", ts_ms: T, soc: 40 });
    for (let t = T + 30_000; t <= T + 120_000; t += 30_000) {
      s.applySample({ address: "A", ts_ms: t, link_event: "Connected" });
    }
    const a = s.getFleet()["A"];
    expect(a.ts_ms).toBe(T);
    expect(staleAddresses([a], T + 120_000)).toEqual(new Set(["A"]));
  });
});
