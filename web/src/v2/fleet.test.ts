import { describe, expect, it } from "vitest";
import { groupBases, deltaMv } from "./fleet";
import type { FleetItem } from "../types";

const mk = (o: Partial<FleetItem>): FleetItem => ({ address: "x", ts_ms: 1, ...o });

describe("groupBases", () => {
  const items = [
    mk({ address: "a1", alias: "2012-A", group_id: "2012", soc: 88, current_a: -4 }),
    mk({ address: "a2", alias: "2012-B", group_id: "2012", soc: 90, current_a: -3 }),
    mk({ address: "b1", alias: "2016-A", group_id: "2016", soc: 100, current_a: 5 }),
    mk({ address: "b2", alias: "2016-B", group_id: "2016", soc: 99, current_a: 4 }),
  ];
  it("groups by base, labels A/B by alias order, daily-driver first", () => {
    const bases = groupBases(items, new Set());
    expect(bases[0].id).toBe("2012");
    expect(bases[0].packs.map((p) => p.letter)).toEqual(["A", "B"]);
    expect(bases[0].status).toBe("in-use");
    expect(bases.find((b) => b.id === "2016")!.status).toBe("charging");
  });
  it("marks a base offline when all packs are stale", () => {
    const bases = groupBases(items, new Set(["b1", "b2"]));
    expect(bases.find((b) => b.id === "2016")!.status).toBe("offline");
  });
});

describe("deltaMv", () => {
  it("returns spread in mV from cells", () => {
    expect(deltaMv(mk({ cells: [3.30, 3.34, 3.31, 3.32] }))).toBeCloseTo(40, 5);
  });
  it("falls back to cell_min_v/cell_max_v", () => {
    expect(deltaMv(mk({ cell_min_v: 3.30, cell_max_v: 3.35 }))).toBeCloseTo(50, 5);
  });
  it("returns null with no cell data", () => {
    expect(deltaMv(mk({}))).toBeNull();
  });
});
