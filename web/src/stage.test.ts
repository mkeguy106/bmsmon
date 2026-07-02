import { describe, expect, it } from "vitest";
import { selectStageItems } from "./stage";
import type { FleetItem } from "./types";

const mk = (address: string, over: Partial<FleetItem> = {}): FleetItem => ({
  address, ts_ms: 1000, alias: address, group_id: null, current_a: 0, ...over,
});

const none = new Set<string>();

describe("selectStageItems", () => {
  it("pinned packs win outright, alias-ordered", () => {
    const items = [
      mk("A", { alias: "2012 · A", group_id: "2012", current_a: -5 }), // would win auto
      mk("C", { alias: "2024 · B", group_id: "2024" }),
      mk("B", { alias: "2024 · A", group_id: "2024" }),
    ];
    const out = selectStageItems(items, none, new Set(["C", "B"]));
    expect(out.map((i) => i.address)).toEqual(["B", "C"]);
  });

  it("a stale pin (address no longer in the fleet) falls back to auto", () => {
    const items = [
      mk("A", { alias: "2012 · A", group_id: "2012", current_a: -5, ts_ms: 2000 }),
      mk("B", { alias: "2012 · B", group_id: "2012" }),
    ];
    const out = selectStageItems(items, none, new Set(["GONE"]));
    expect(out.map((i) => i.address)).toEqual(["A", "B"]); // auto base selection
  });

  it("auto picks the active (discharging) base and expands the whole group", () => {
    const items = [
      mk("A", { alias: "2012 · A", group_id: "2012", ts_ms: 9000 }), // most recent but idle
      mk("B", { alias: "2016 · A", group_id: "2016", current_a: -4.2 }), // discharging → lead
      mk("C", { alias: "2016 · B", group_id: "2016", current_a: 0 }),
    ];
    const out = selectStageItems(items, none, none);
    expect(out.map((i) => i.address)).toEqual(["B", "C"]); // whole 2016 base, alias order
  });

  it("no discharging pack: the freshest fresh pack leads; stale packs only lead as last resort", () => {
    const items = [
      mk("A", { group_id: "g1", ts_ms: 5000 }), // stale
      mk("B", { group_id: "g2", ts_ms: 3000 }), // fresh, newest fresh → lead
      mk("C", { group_id: "g2", ts_ms: 1000 }),
    ];
    expect(selectStageItems(items, new Set(["A"]), none).map((i) => i.address)).toEqual(["B", "C"]);
    // Everything stale → most recent overall still gives a stage (shown dimmed).
    expect(selectStageItems(items, new Set(["A", "B", "C"]), none).map((i) => i.address))
      .toEqual(["A"]);
  });

  it("a lead without a group stands alone", () => {
    const items = [mk("A", { group_id: null, current_a: -2 }), mk("B", { group_id: "g" })];
    expect(selectStageItems(items, none, none).map((i) => i.address)).toEqual(["A"]);
  });

  it("empty fleet → empty stage", () => {
    expect(selectStageItems([], none, none)).toEqual([]);
    expect(selectStageItems([], none, new Set(["A"]))).toEqual([]);
  });
});
