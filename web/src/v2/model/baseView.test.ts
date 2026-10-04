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

// Task 5 carry: the stage's flow label comes from the view, the same live packs at the same
// moment as the watts it labels (base.status read a regen burst as CHARGING).
describe("baseView flow direction", () => {
  it("reads draw, charge, regen and idle from the live packs, null with none live", () => {
    expect(baseView(base({ current_a: -5 }, { current_a: -5 }), ctx()).flowDir).toBe("draw");
    expect(baseView(base({ current_a: 8 }, { current_a: 8 }), ctx()).flowDir).toBe("charge");
    expect(baseView(base({ current_a: 5, regen: true }, { current_a: 5, regen: true }), ctx()).flowDir).toBe("regen");
    expect(baseView(base({ current_a: 0 }, { current_a: 0.05 }), ctx()).flowDir).toBe("idle");
    expect(baseView(base({ current_a: -5 }, { current_a: -5 }, ["A", "B"]), ctx()).flowDir).toBeNull();
  });
  it("ignores a stale pack's current", () => {
    expect(baseView(base({ current_a: -9 }, { current_a: 0 }, ["A"]), ctx()).flowDir).toBe("idle");
  });
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

  // Review fix round 1, Minor #1: intended. The stale pack's real charge is only bounded
  // above by its last reading, so the shown bound is uncertain whichever pack sets the min.
  it("names a stale pack in the bound even when a live pack sets the minimum", () => {
    const v = baseView(base({ remaining_ah: 55, ts_ms: NOW - 3_600_000 }, { remaining_ah: 20 }, ["A"]), ctx());
    if (v.range.kind !== "estimate") throw new Error(v.range.kind);
    expect(v.range.range.milesHi).toBeCloseTo(seedMilesHi(20), 6);
    expect(v.range.lastKnown).toEqual({ letter: "A", tsMs: NOW - 3_600_000 });
    expect(v.usableLastKnown).toEqual({ letter: "A", tsMs: NOW - 3_600_000 });
  });

  // Review fix round 1, Important #2: range.ts returns no estimate for a bad band, which used
  // to drop the weaker pack from the bound and show its partner's miles.
  it("keeps a pack with invalid synced params in the bound, on the seed", () => {
    const bad: RangeParams[] = [
      { ...SEED_RANGE_PARAMS, whPerMile: { lo: 0, hi: 70 } },
      { ...SEED_RANGE_PARAMS, whPerDay: { lo: 78, hi: NaN } },
      { ...SEED_RANGE_PARAMS, activeW: { lo: -5, hi: 97.5 } },
      { ...SEED_RANGE_PARAMS, whPerMile: { lo: 51, hi: Infinity } },
      { ...SEED_RANGE_PARAMS, whPerMile: { lo: 80, hi: 40 } },   // inverted band
      { ...SEED_RANGE_PARAMS, whPerDay: { lo: 200, hi: 100 } },
    ];
    for (const p of bad) {
      const v = baseView(base({ remaining_ah: 10 }, { remaining_ah: 55 }), ctx({ rangeParams: new Map([["A", p]]) }));
      if (v.range.kind !== "estimate") throw new Error(v.range.kind);
      expect(v.range.range.milesHi).toBeCloseTo(seedMilesHi(10), 6);
      expect(v.packParams[0]).toBe(SEED_RANGE_PARAMS);
      expect(v.usableWh).toBeCloseTo(2 * 10 * 12.8, 6);
    }
  });

  // Review fix round 1, Important #1: a pack with no usable capacity could be the weaker one,
  // so the base has no bound. The reviewer's probe read 768 Wh / 7.5 mi here.
  it("reads no-data when any pack lacks a usable capacity, live or last known", () => {
    const cases: Array<[Partial<FleetItem>, Partial<FleetItem>, string[]]> = [
      [{ remaining_ah: 30 }, { remaining_ah: null }, []],
      [{ remaining_ah: 30 }, { remaining_ah: -3 }, []],
      [{ remaining_ah: null }, { remaining_ah: 40 }, ["A"]],
    ];
    for (const [a, b, stale] of cases) {
      const v = baseView(base(a, b, stale), ctx());
      expect(v.range).toEqual({ kind: "no-data" });
      expect(v.usableWh).toBeNull();
      expect(v.usableLastKnown).toBeNull();
    }
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
    expect(v.range).toEqual({ kind: "charging", etaFullMin: 120, partial: null });
  });

  // Task 5 carry: a pack that is not live has no time to full in the figure, so the base may
  // be ready later than shown. The view names it, never posing as the whole base's ETA.
  it("flags a charging ETA as partial while a pack is not live", () => {
    const v = baseView(base({ current_a: 8, eta_full_min: 90 }, { ts_ms: NOW - 300_000 }, ["B"]), ctx());
    expect(v.range).toEqual({ kind: "charging", etaFullMin: 90, partial: { letter: "B", tsMs: NOW - 300_000 } });
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

  // Review fix round 1: with both packs live, the old Journey basis summed the packs that
  // reported a capacity > 0. The series bound must never read higher than that.
  it("never exceeds the old Journey sum when both packs are live", () => {
    const values = [null, -3, NaN, 0, 14, 30, 55];
    const oldSum = (...ahs: Array<number | null>) => ahs
      .filter((ah): ah is number => ah != null && Number.isFinite(ah) && ah > 0)
      .reduce((s, ah) => s + ah * 12.8, 0);
    for (const a of values) {
      for (const b of values) {
        const wh = baseView(base({ remaining_ah: a }, { remaining_ah: b }), ctx()).usableWh;
        const unknown = [a, b].some((ah) => ah == null || !Number.isFinite(ah) || ah < 0);
        if (unknown) expect(wh, `${a}/${b}`).toBeNull();
        else expect(wh!, `${a}/${b}`).toBeLessThanOrEqual(oldSum(a, b));
      }
    }
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
