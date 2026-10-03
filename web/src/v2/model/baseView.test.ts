import { describe, expect, it } from "vitest";
import type { FleetItem } from "../../types";
import type { TempConfig } from "../../temp";
import { SEED_RANGE_PARAMS, type RangeParams } from "../../range";
import { groupBases, type Base } from "../fleet";
import { baseView, type BaseViewInputs } from "./baseView";

const NOW = 10_000_000;
const item = (address: "A" | "B", o: Partial<FleetItem>): FleetItem => ({
  address, group_id: "2012", alias: `2012 · ${address}`, ts_ms: NOW - 1_000,
  soc: 60, remaining_ah: 60, current_a: -5, power_w: -64, temp_c: 22, regen: false, ...o,
});
/** Base 2012 with packs A and B; `stale` lists the addresses that are not live. */
const base = (a: Partial<FleetItem>, b: Partial<FleetItem>, stale: string[] = []): Base =>
  groupBases([item("A", a), item("B", b)], new Set(stale))[0];
const ctx = (o: Partial<BaseViewInputs> = {}): BaseViewInputs =>
  ({ rangeParams: new Map(), tempConfig: null, ...o });
/** SEED whPerMile.lo = 51 Wh/mi, so a pack's milesHi = remaining Ah × 12.8 / 51. */
const seedMilesHi = (ah: number) => (ah * 12.8) / 51;
const tempCfg = (o: Partial<TempConfig> = {}): TempConfig => ({
  device_id: "d", profile_id: "redodo", cold_caution_c: 5, hot_caution_c: 45,
  cold_crit_c: -12, hot_crit_c: 53, unit: "F", updated_at_ms: 0, received_at: "", ...o,
});

describe("baseView packs and flow", () => {
  it("keeps every pack with its own live flag, and sums flow over live packs only", () => {
    const v = baseView(base({ power_w: -300 }, { power_w: -64 }, ["A"]), ctx());
    expect(v.packs.map((p) => [p.letter, p.live])).toEqual([["A", false], ["B", true]]);
    expect(v.livePacks.map((p) => p.letter)).toEqual(["B"]);
    expect(v.flowW).toBe(64);
  });
});

describe("baseView range", () => {
  it("bounds by a pack that dropped off BLE, and names it (WEB-14)", () => {
    const v = baseView(base({ remaining_ah: 14, soc: 14, ts_ms: NOW - 180_000 }, { remaining_ah: 55 }, ["A"]), ctx());
    expect(v.range.kind).toBe("estimate");
    if (v.range.kind !== "estimate") return;
    expect(v.range.range.milesHi).toBeCloseTo(seedMilesHi(14), 6);
    expect(v.range.lastKnown).toEqual({ letter: "A", tsMs: NOW - 180_000 });
  });

  it("bounds by the weaker live pack and flags nothing when both are live", () => {
    const v = baseView(base({ remaining_ah: 55 }, { remaining_ah: 20 }), ctx());
    if (v.range.kind !== "estimate") throw new Error(v.range.kind);
    expect(v.range.range.milesHi).toBeCloseTo(seedMilesHi(20), 6);
    expect(v.range.lastKnown).toBeNull();
  });

  it("reads offline when no pack is live, with the newest sample as last seen (WEB-19)", () => {
    const v = baseView(base({ ts_ms: NOW - 60_000 }, { ts_ms: NOW - 120_000 }, ["A", "B"]), ctx());
    expect(v.range).toEqual({ kind: "offline", lastSeenMs: NOW - 60_000 });
    expect(v.flowW).toBeNull();
    expect(v.charging).toBe(false);
  });

  it("gives the slot to a live pack that is charging, with the longest ETA", () => {
    const v = baseView(base({ current_a: 8, eta_full_min: 90 }, { current_a: 8, eta_full_min: 120 }), ctx());
    expect(v.charging).toBe(true);
    expect(v.range).toEqual({ kind: "charging", etaFullMin: 120 });
  });

  // Review Focus: regen pushes current positive for up to ~23 s while driving.
  it("does not treat a regen burst while driving as charging", () => {
    const v = baseView(base({ current_a: 5, regen: true }, { current_a: 5, regen: true }), ctx());
    expect(v.charging).toBe(false);
    expect(v.range.kind).toBe("estimate");
  });

  it("reads no-data, not charging, when live packs report no capacity (WEB-19)", () => {
    const v = baseView(base({ remaining_ah: null }, { remaining_ah: null }), ctx());
    expect(v.range).toEqual({ kind: "no-data" });
  });

  // Review Focus: range.ts treats remaining_ah <= 0 as "no data", which would drop an EMPTY
  // pack from the bound and show the other pack's miles.
  it("bounds the base to zero for a pack at 0 Ah instead of skipping it", () => {
    const v = baseView(base({ remaining_ah: 0, soc: 0 }, { remaining_ah: 55 }), ctx());
    if (v.range.kind !== "estimate") throw new Error(v.range.kind);
    expect(v.range.range.milesHi).toBe(0);
    expect(v.range.range.activeHHi).toBe(0);
    expect(v.usableWh).toBe(0);
  });
});

describe("baseView usable energy", () => {
  it("is pack count × the weaker pack, not the sum (WEB-15)", () => {
    const v = baseView(base({ remaining_ah: 30 }, { remaining_ah: 50 }), ctx());
    expect(v.usableWh).toBeCloseTo(2 * 30 * 12.8, 6); // 768 Wh, not (30 + 50) × 12.8 = 1024
    expect(v.usableLastKnown).toBeNull();
  });

  it("counts and names a last-known pack; a missing or garbage reading counts as none", () => {
    const v = baseView(base({ remaining_ah: 30, ts_ms: NOW - 200_000 }, { remaining_ah: 50 }, ["A"]), ctx());
    expect(v.usableWh).toBeCloseTo(768, 6);
    expect(v.usableLastKnown).toEqual({ letter: "A", tsMs: NOW - 200_000 });
    expect(baseView(base({ remaining_ah: null }, { remaining_ah: -3 }), ctx()).usableWh).toBeNull();
  });
});

describe("baseView thermal (WEB-31)", () => {
  it("follows the synced zones on both sides instead of a fixed 44 °C", () => {
    expect(baseView(base({ temp_c: 44 }, {}), ctx()).thermal).toBeNull(); // below hot caution 45
    const warm = baseView(base({ temp_c: 45 }, {}), ctx()).thermal!;
    expect([warm.zone.key, warm.severity, warm.letter, warm.title]).toEqual(["cautionHot", "warning", "A", "Running warm"]);
    const cold = baseView(base({}, { temp_c: -15 }), ctx()).thermal!;
    expect([cold.zone.key, cold.severity, cold.letter]).toEqual(["critCold", "critical", "B"]);
    const synced = baseView(base({ temp_c: 42 }, {}), ctx({ tempConfig: tempCfg({ hot_caution_c: 40 }) })).thermal!;
    expect(synced.zone.key).toBe("cautionHot");
  });

  it("ignores a stale pack's temperature, and the worst live pack wins", () => {
    expect(baseView(base({ temp_c: -25 }, {}, ["A"]), ctx()).thermal).toBeNull();
    const worst = baseView(base({ temp_c: 46 }, { temp_c: -15 }), ctx()).thermal!;
    expect([worst.letter, worst.severity]).toEqual(["B", "critical"]);
  });
});

describe("baseView range params", () => {
  it("falls back to the seed per pack, for every pack", () => {
    const learned: RangeParams = { ...SEED_RANGE_PARAMS, whPerMile: { lo: 40, hi: 70 }, learnedDays: 5 };
    const v = baseView(base({}, {}, ["B"]), ctx({ rangeParams: new Map([["A", learned]]) }));
    expect(v.packParams).toEqual([learned, SEED_RANGE_PARAMS]);
  });
});
