import { describe, expect, it } from "vitest";
import { healthSummary, healthBoardOrder, packStatus } from "./health";
import type { FleetItem } from "../../types";

const mk = (o: Partial<FleetItem>): FleetItem => ({ address: "x", ts_ms: 1, ...o });

describe("healthSummary", () => {
  const items = [
    mk({ address: "a", soc: 95, soh: 99, remaining_ah: 95, full_charge_ah: 100 }),
    mk({ address: "b", soc: 20, soh: 99, remaining_ah: 20, full_charge_ah: 100 }),
    mk({ address: "c", soc: 88, soh: 72, remaining_ah: 88, full_charge_ah: 100 }),
    mk({ address: "d", soc: 100, soh: 99, remaining_ah: 100, full_charge_ah: 100 }),
  ];
  it("counts ready/recharge/degraded and fleet capacity, excluding stale from live counts", () => {
    const s = healthSummary(items, new Set(["d"]));
    expect(s.ready).toBe(1);          // a (95); d is stale
    expect(s.needRecharge).toBe(1);   // b (20)
    expect(s.degraded).toBe(1);       // c (soh 72) — degraded counts regardless of stale
    expect(Math.round(s.capacityPct)).toBe(68); // (95+20+88)/(300) over connected a,b,c
  });
  it("capacityPct is 0 when no connected pack has capacity", () => {
    expect(healthSummary([mk({ address: "a" })], new Set()).capacityPct).toBe(0);
  });
});

describe("healthBoardOrder", () => {
  it("puts disconnected first, then ascending SOC", () => {
    const items = [mk({ address: "a", soc: 80 }), mk({ address: "b", soc: 20 }), mk({ address: "c", soc: 50 })];
    const order = healthBoardOrder(items, new Set(["c"])).map((i) => i.address);
    expect(order).toEqual(["c", "b", "a"]); // c disconnected first; then 20, 80
  });
});

describe("packStatus", () => {
  it("classifies", () => {
    expect(packStatus(mk({ current_a: -4, soc: 80 }), true)).toBe("in-use");
    expect(packStatus(mk({ current_a: 5, soc: 80 }), true)).toBe("charging");
    expect(packStatus(mk({ current_a: 0, soc: 20 }), true)).toBe("low");
    expect(packStatus(mk({ current_a: 0, soc: 80 }), true)).toBe("idle");
    expect(packStatus(mk({ soc: 80 }), false)).toBe("offline");
  });
});
