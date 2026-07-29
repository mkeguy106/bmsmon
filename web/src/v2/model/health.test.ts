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
  it("counts ready/recharge/degraded on last-known readings, stale included", () => {
    const s = healthSummary(items, new Set(["d"]));
    expect(s.ready).toBe(2);          // a (95) live + d (100) offline on its last reading
    expect(s.readyStale).toBe(1);     // …of which d is last-known
    expect(s.needRecharge).toBe(1);   // b (20)
    expect(s.needRechargeStale).toBe(0);
    expect(s.degraded).toBe(1);       // c (soh 72)
  });
  it("reports an offline LOW pack in needRecharge", () => {
    const s = healthSummary(items, new Set(["b"]));
    expect(s.needRecharge).toBe(1);
    expect(s.needRechargeStale).toBe(1);
  });
  it("counts no pack whose SOC is unknown", () => {
    const s = healthSummary([mk({ address: "z" })], new Set(["z"]));
    expect(s.ready).toBe(0);
    expect(s.needRecharge).toBe(0);
  });
  it("folds an offline pack's LAST-KNOWN capacity into capacityPct", () => {
    const s = healthSummary(items, new Set(["d"]));
    // (95+20+88+100)/400 — stale d still holds its charge, so it counts.
    expect(Math.round(s.capacityPct)).toBe(76);
    expect(s.staleCounted).toBe(1);
  });
  it("staleCounted is 0 with every pack live", () => {
    const s = healthSummary(items, new Set());
    expect(Math.round(s.capacityPct)).toBe(76);
    expect(s.staleCounted).toBe(0);
  });
  it("capacityPct is 0 when no pack reports capacity", () => {
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
