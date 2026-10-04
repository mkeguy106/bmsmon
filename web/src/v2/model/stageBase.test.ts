import { describe, expect, it } from "vitest";
import type { FleetItem } from "../../types";
import { groupBases } from "../fleet";
import {
  ACTIVE_HOLD_MS, PIN_HOLD_MS, decodeStagePin, foldDischarge, sameSelection, seizeThresholdFrom,
  selectStageBase, type StageInputs,
} from "./stageBase";

const NOW = 10_000_000;
const pack = (address: string, group_id: string, o: Partial<FleetItem> = {}): FleetItem =>
  ({ address, group_id, alias: `${group_id} · ${address.slice(-1)}`, ts_ms: NOW - 1_000,
     soc: 80, current_a: 0, ...o });
/** The real fleet shape: four bases of two packs. */
const fleet = (over: Record<string, Partial<FleetItem>> = {}): FleetItem[] =>
  ["2012", "2016", "2023", "2024"].flatMap((g) =>
    ["A", "B"].map((l) => pack(`${g}${l}`, g, over[`${g}${l}`])));

function run(items: FleetItem[], o: Partial<Omit<StageInputs, "bases">> & { stale?: string[] } = {}) {
  const { stale = [], ...rest } = o;
  return selectStageBase({
    bases: groupBases(items, new Set(stale)), seizeThreshold: 30, pin: null,
    lastDischargeMs: new Map(), sticky: null, nowMs: NOW, ...rest,
  });
}
/** The spares at home, out of BLE range while the chair is out. */
const AWAY = ["2012A", "2012B", "2023A", "2023B", "2024A", "2024B"];

describe("seizeThresholdFrom (C6)", () => {
  it("is off when alerts are off, the pushed value otherwise, 30 when none was pushed", () => {
    expect(seizeThresholdFrom({ alerts_on: false, seize_soc: 40 })).toBeNull();
    expect(seizeThresholdFrom({ alerts_on: true, seize_soc: 25 })).toBe(25);
    expect(seizeThresholdFrom({ alerts_on: true, seize_soc: null })).toBe(30);
    expect(seizeThresholdFrom({ alerts_on: true, seize_soc: 0 })).toBe(0);
  });

  // Final review: a seize staged on the default 30 before /web/alert-config answered could
  // stick (PARKED) even when the phone's config turned out to have alerts off.
  it("is off while the config is still unknown", () => {
    expect(seizeThresholdFrom(null)).toBeNull();
  });
});

describe("selectStageBase", () => {
  it("is null with no fleet yet", () => {
    expect(selectStageBase({ bases: [], seizeThreshold: 30, pin: null, lastDischargeMs: new Map(),
      sticky: null, nowMs: NOW })).toBeNull();
  });

  // WEB-12's scenario: 2012 on the charger at home (out of range), riding on 2016.
  it("follows the base in use, not the hardcoded daily driver", () => {
    const sel = run(fleet({ "2016A": { current_a: -6 }, "2016B": { current_a: -6 } }), { stale: AWAY });
    expect(sel).toEqual({ baseId: "2016", reason: "in-use" });
  });

  it("seizes for the lowest SOC among the packs of the base in use, over a pin", () => {
    const pin = { baseId: "2023", atMs: NOW - 60_000 };
    const items = fleet({ "2016A": { current_a: -6, soc: 25 }, "2016B": { current_a: -6, soc: 18 } });
    expect(run(items, { pin })).toEqual({ baseId: "2016", reason: "seize" });
  });

  it("an idle low spare never displaces the discharging base", () => {
    const sel = run(fleet({ "2016A": { current_a: -6 }, "2023B": { soc: 22 }, "2024A": { soc: 18 } }));
    expect(sel).toEqual({ baseId: "2016", reason: "in-use" });
  });

  it("a charging low spare never seizes, even with nothing else in use", () => {
    expect(run(fleet({ "2023A": { soc: 10, current_a: 5 } }))).toEqual({ baseId: "2012", reason: "default" });
    expect(run(fleet({ "2023A": { soc: 10, state: "Charging" } }))).toEqual({ baseId: "2012", reason: "default" });
  });

  it("breaks an equal-SOC seize tie by pack address after the daily driver, as the phone does", () => {
    // Nothing in use; two idle low spares at the same SOC. Base id order would pick 2016, the
    // phone picks the lowest pack ADDRESS, here 2024's.
    const items = [
      pack("Z1", "2016", { soc: 25 }), pack("Z2", "2016", { soc: 80 }),
      pack("A1", "2024", { soc: 25 }), pack("A2", "2024", { soc: 80 }),
    ];
    expect(run(items)).toEqual({ baseId: "2024", reason: "seize" });
  });
  it("prefers the daily driver on an equal-SOC seize tie whatever the addresses", () => {
    const items = [
      pack("Z1", "2012", { soc: 25 }), pack("Z2", "2012", { soc: 80 }),
      pack("A1", "2024", { soc: 25 }), pack("A2", "2024", { soc: 80 }),
    ];
    expect(run(items)).toEqual({ baseId: "2012", reason: "seize" });
  });
  it("with two bases discharging, a low pack on the non-daily-driver one still seizes", () => {
    const sel = run(fleet({ "2012A": { current_a: -6 }, "2016A": { current_a: -6, soc: 20 } }));
    expect(sel).toEqual({ baseId: "2016", reason: "seize" });
  });

  it("a regen (Charging-state) pack on the in-use base still seizes", () => {
    const sel = run(fleet({ "2016A": { current_a: -6 }, "2016B": { state: "Charging", current_a: 2, soc: 15 } }));
    expect(sel).toEqual({ baseId: "2016", reason: "seize" });
    const own = run(fleet({ "2016B": { state: "Charging", current_a: 2, soc: 15, regen: true } }));
    expect(own).toEqual({ baseId: "2016", reason: "seize" });
    const mem = run(fleet({ "2016B": { state: "Charging", current_a: 2, soc: 15 } }),
      { lastDischargeMs: new Map([["2016", NOW - 10_000]]) });
    expect(mem).toEqual({ baseId: "2016", reason: "seize" });
  });

  it("with no base in use, a low idle spare seizes", () => {
    expect(run(fleet({ "2024A": { soc: 12 } }))).toEqual({ baseId: "2024", reason: "seize" });
  });

  it("the held base (discharged within the hold) counts as in use for the seize", () => {
    const lastDischargeMs = new Map([["2016", NOW - 60_000]]);
    expect(run(fleet({ "2023B": { soc: 22 }, "2016A": { soc: 28 } }), { lastDischargeMs }))
      .toEqual({ baseId: "2016", reason: "seize" });
    expect(run(fleet({ "2023B": { soc: 22 } }), { lastDischargeMs }))
      .toEqual({ baseId: "2016", reason: "hold" });
  });

  it("fires the seize at exactly the threshold (≤, the ladder convention)", () => {
    expect(run(fleet({ "2023A": { soc: 30 } }))).toEqual({ baseId: "2023", reason: "seize" });
  });

  it("ignores a stale low pack and a null threshold", () => {
    expect(run(fleet({ "2023A": { soc: 5 } }), { stale: ["2023A"] }))
      .toEqual({ baseId: "2012", reason: "default" });
    expect(run(fleet({ "2023A": { soc: 5 } }), { seizeThreshold: null }))
      .toEqual({ baseId: "2012", reason: "default" });
    expect(run(fleet({ "2023A": { soc: 31 } }))).toEqual({ baseId: "2012", reason: "default" });
  });

  it("breaks an exact seize tie toward the daily driver", () => {
    expect(run(fleet({ "2012B": { soc: 20 }, "2023A": { soc: 20 } }))!.baseId).toBe("2012");
  });

  // Review focus: v1 and android both let the seize override a pin, and both restore the
  // pin once the low pack recovers.
  it("seize overrides a fresh pin; the pin returns once the pack recovers", () => {
    const pin = { baseId: "2023", atMs: NOW - 60_000 };
    expect(run(fleet({ "2024A": { soc: 12 } }), { pin })).toEqual({ baseId: "2024", reason: "seize" });
    expect(run(fleet({ "2024A": { soc: 45 } }), { pin })).toEqual({ baseId: "2023", reason: "pin" });
  });

  it("a fresh pin outranks the base in use; after PIN_HOLD_MS the chair takes back over", () => {
    const items = fleet({ "2012A": { current_a: -5 } });
    expect(run(items, { pin: { baseId: "2023", atMs: NOW - PIN_HOLD_MS + 1 } }))
      .toEqual({ baseId: "2023", reason: "pin" });
    expect(run(items, { pin: { baseId: "2023", atMs: NOW - PIN_HOLD_MS } }))
      .toEqual({ baseId: "2012", reason: "in-use" });
  });

  // Final review: a pin dated AHEAD of the clock (it stepped back since the tap) used to
  // have a negative age, which is always "< PIN_HOLD_MS" — so it never expired.
  it("expires a future-dated pin once it is PIN_HOLD_MS ahead of the clock", () => {
    const items = fleet({ "2012A": { current_a: -5 } });
    expect(run(items, { pin: { baseId: "2023", atMs: NOW + PIN_HOLD_MS - 1 } }))
      .toEqual({ baseId: "2023", reason: "pin" });
    expect(run(items, { pin: { baseId: "2023", atMs: NOW + PIN_HOLD_MS } }))
      .toEqual({ baseId: "2012", reason: "in-use" });
  });

  it("ignores a pin for a base that no longer exists", () => {
    expect(run(fleet(), { pin: { baseId: "1999", atMs: NOW } })).toEqual({ baseId: "2012", reason: "default" });
  });

  it("a stale pack's last discharging sample does not put its base in use", () => {
    expect(run(fleet({ "2016A": { current_a: -9 } }), { stale: ["2016A"] }))
      .toEqual({ baseId: "2012", reason: "default" });
  });

  it("deepest draw wins when two bases discharge", () => {
    const sel = run(fleet({ "2012A": { current_a: -3 }, "2016B": { current_a: -9 } }));
    expect(sel).toEqual({ baseId: "2016", reason: "in-use" });
  });

  it("holds the last discharging base for 15 min after it stops, then lets go", () => {
    const items = fleet();
    expect(run(items, { lastDischargeMs: new Map([["2016", NOW - ACTIVE_HOLD_MS + 1]]) }))
      .toEqual({ baseId: "2016", reason: "hold" });
    expect(run(items, { lastDischargeMs: new Map([["2016", NOW - ACTIVE_HOLD_MS]]) }))
      .toEqual({ baseId: "2012", reason: "default" });
  });

  it("keeps holding while the held base's packs drop out of range (shows it disconnected)", () => {
    const sel = run(fleet(), { stale: ["2016A", "2016B"], lastDischargeMs: new Map([["2016", NOW - 60_000]]) });
    expect(sel).toEqual({ baseId: "2016", reason: "hold" });
  });

  it("ranks in use over hold over parked", () => {
    const hold = new Map([["2016", NOW - 60_000]]);
    expect(run(fleet({ "2023A": { current_a: -4 } }), { lastDischargeMs: hold, sticky: "2024" }))
      .toEqual({ baseId: "2023", reason: "in-use" });
    expect(run(fleet(), { lastDischargeMs: hold, sticky: "2024" }))
      .toEqual({ baseId: "2016", reason: "hold" });
    expect(run(fleet(), { sticky: "2024" })).toEqual({ baseId: "2024", reason: "parked" });
  });

  it("stays parked on the previous base while it is still reporting", () => {
    expect(run(fleet(), { sticky: "2023" })).toEqual({ baseId: "2023", reason: "parked" });
  });

  it("drops a parked base once every pack is stale", () => {
    expect(run(fleet(), { sticky: "2023", stale: ["2023A", "2023B"] }))
      .toEqual({ baseId: "2012", reason: "default" });
  });

  // Review focus: parked at work all day on 2016, page freshly loaded — no discharge memory,
  // no previous answer. The stage must not open on the daily driver sitting at home.
  it("on a cold load away from home, picks the only reporting base over the offline daily driver", () => {
    expect(run(fleet(), { stale: AWAY })).toEqual({ baseId: "2016", reason: "default" });
  });

  it("with several bases reporting and the daily driver offline, takes the newest sample", () => {
    const items = fleet({ "2023A": { ts_ms: NOW - 500 } });
    expect(run(items, { stale: ["2012A", "2012B"] })).toEqual({ baseId: "2023", reason: "default" });
  });

  // Controller ruling (final review): the phone polls its own stage base fastest, so with
  // nothing reporting the newest sample is still the best guess at the chair — e.g. the
  // phone went offline mid-outing on 2016 while 2012 sat on the charger at home.
  it("with nothing reporting, stages the base with the newest sample, stale included", () => {
    // The spares last reported when the chair left home 3 h ago; 2016 until 20 min ago.
    const items = fleet().map((i) =>
      ({ ...i, ts_ms: i.group_id === "2016" ? NOW - 20 * 60_000 : NOW - 3 * 3_600_000 }));
    expect(run(items, { stale: items.map((i) => i.address) }))
      .toEqual({ baseId: "2016", reason: "default" });
  });

  it("falls back to the daily driver only when no pack has a sample time at all", () => {
    const items = fleet().map((i) => ({ ...i, ts_ms: Number.NaN }));
    expect(run(items, { stale: items.map((i) => i.address) }))
      .toEqual({ baseId: "2012", reason: "default" });
  });
});

describe("foldDischarge", () => {
  const bases = (items: FleetItem[], stale: string[] = []) => groupBases(items, new Set(stale));

  it("records the newest discharging sample per base", () => {
    const m = foldDischarge(new Map(), bases(fleet({ "2016A": { current_a: -4, ts_ms: NOW - 2_000 } })));
    expect([...m]).toEqual([["2016", NOW - 2_000]]);
  });

  it("ignores stale packs and returns the same map when nothing advanced", () => {
    const prev = new Map([["2016", NOW - 2_000]]);
    expect(foldDischarge(prev, bases(fleet({ "2016A": { current_a: -4, ts_ms: NOW - 2_000 } })))).toBe(prev);
    expect(foldDischarge(prev, bases(fleet({ "2023A": { current_a: -4 } }), ["2023A"]))).toBe(prev);
  });

  it("never moves a base's discharge time backwards", () => {
    const prev = new Map([["2016", NOW]]);
    expect(foldDischarge(prev, bases(fleet({ "2016A": { current_a: -4, ts_ms: NOW - 5_000 } })))).toBe(prev);
  });
});

describe("pin storage + selection equality", () => {
  it("decodes a valid pin and rejects garbage", () => {
    expect(decodeStagePin('{"baseId":"2016","atMs":5}')).toEqual({ baseId: "2016", atMs: 5 });
    for (const raw of ["null", "", "{", '{"baseId":"","atMs":5}', '{"baseId":"2016"}', '"2016"']) {
      expect(decodeStagePin(raw)).toBeNull();
    }
  });

  it("compares selections by base and reason", () => {
    expect(sameSelection({ baseId: "2012", reason: "hold" }, { baseId: "2012", reason: "hold" })).toBe(true);
    expect(sameSelection({ baseId: "2012", reason: "hold" }, { baseId: "2012", reason: "parked" })).toBe(false);
    expect(sameSelection(null, null)).toBe(true);
  });
});
