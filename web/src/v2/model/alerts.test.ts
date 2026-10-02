import { describe, expect, it } from "vitest";
import {
  ackAlert, deriveAlerts, isAcked, pruneAcks, unackedCount, type AckMap, type V2Alert,
} from "./alerts";
import type { FleetItem } from "../../types";

const mk = (o: Partial<FleetItem>): FleetItem => ({ address: "x", ts_ms: 100, ...o });

describe("deriveAlerts", () => {
  it("fires a warning capacity alert at the crossed rung", () => {
    const a = deriveAlerts([mk({ address: "a", soc: 22 })], new Set(), null);
    const cap = a.find((x) => x.kind === "capacity")!;
    expect(cap.severity).toBe("warning");   // 22 → rung 25, not ≤15
    expect(cap.id).toBe("cap:a");
  });
  it("capacity is critical at/below 15", () => {
    expect(deriveAlerts([mk({ address: "a", soc: 12 })], new Set(), null)
      .find((x) => x.kind === "capacity")!.severity).toBe("critical");
  });
  it("no capacity alert above the top rung", () => {
    expect(deriveAlerts([mk({ address: "a", soc: 40 })], new Set(), null)
      .some((x) => x.kind === "capacity")).toBe(false);
  });
  it("fires a critical temp alert when hot", () => {
    const a = deriveAlerts([mk({ address: "a", soc: 80, temp_c: 55 })], new Set(), null);
    expect(a.find((x) => x.kind === "temp")!.severity).toBe("critical"); // ≥ hotCrit 53
  });
  it("fires a cell-imbalance warning over 40 mV", () => {
    const a = deriveAlerts([mk({ address: "a", soc: 80, cells: [3.30, 3.36, 3.31, 3.32] })], new Set(), null);
    expect(a.find((x) => x.kind === "cell")!.severity).toBe("warning"); // Δ 60 mV → warning (>40, ≤60)
  });
  it("excludes disconnected packs", () => {
    expect(deriveAlerts([mk({ address: "a", soc: 5 })], new Set(["a"]), null)).toHaveLength(0);
  });
  it("sorts critical before warning", () => {
    const a = deriveAlerts([mk({ address: "a", soc: 22 }), mk({ address: "b", soc: 8 })], new Set(), null);
    expect(a[0].severity).toBe("critical");
  });
});

describe("ack lifecycle (WEB-17)", () => {
  const NONE: AckMap = new Map();
  const capAt = (soc: number): V2Alert =>
    deriveAlerts([mk({ address: "b", soc })], new Set(), null).find((x) => x.kind === "capacity")!;
  const tempAt = (c: number): V2Alert =>
    deriveAlerts([mk({ address: "b", soc: 80, temp_c: c })], new Set(), null).find((x) => x.kind === "temp")!;
  const cellAt = (cells: number[]): V2Alert =>
    deriveAlerts([mk({ address: "b", soc: 80, cells })], new Set(), null).find((x) => x.kind === "cell")!;

  it("ranks capacity by the rung crossed — lower rung, higher rank — under one stable id", () => {
    expect([capAt(28), capAt(24), capAt(9), capAt(5)].map((a) => a.rank)).toEqual([1, 2, 5, 6]);
    expect(new Set([capAt(28).id, capAt(9).id])).toEqual(new Set(["cap:b"]));
  });

  // The review's scenario: ack 2012-B at 28% in the morning, it reaches 9% that afternoon.
  it("acking a 28% warning does not hide the same pack's later 9% critical", () => {
    const acked = ackAlert(NONE, capAt(28));
    expect(isAcked(acked, capAt(28))).toBe(true);
    const later = capAt(9);
    expect(later.severity).toBe("critical");
    expect(isAcked(acked, later)).toBe(false);
    expect(unackedCount([later], acked)).toBe(1);
  });

  it("re-arms at the next rung down (android: silence until the next level)", () => {
    expect(isAcked(ackAlert(NONE, capAt(28)), capAt(24))).toBe(false);
  });

  it("stays acked while the pack charges back up through the rungs", () => {
    const acked = ackAlert(NONE, capAt(9));
    expect(isAcked(acked, capAt(12))).toBe(true);
    expect(isAcked(acked, capAt(28))).toBe(true);
  });

  it("re-arms a temperature ack on escalation, critHot → cutoffHot (v1 nextAckedKey parity)", () => {
    const acked = ackAlert(NONE, tempAt(55));      // critHot, rank 3
    expect(isAcked(acked, tempAt(55))).toBe(true);
    expect(isAcked(acked, tempAt(61))).toBe(false); // cutoffHot, rank 4
  });

  it("keeps temperature acks per side: a hot ack never silences a cold alert", () => {
    const acked = ackAlert(NONE, tempAt(55));
    const cold = tempAt(-15);                       // critCold, same rank 3
    expect(cold.id).not.toBe(tempAt(55).id);
    expect(isAcked(acked, cold)).toBe(false);
  });

  it("re-arms a cell-imbalance ack when it turns critical", () => {
    const acked = ackAlert(NONE, cellAt([3.30, 3.35, 3.31, 3.32]));     // Δ50 → warning
    expect(isAcked(acked, cellAt([3.30, 3.38, 3.31, 3.32]))).toBe(false); // Δ80 → critical
  });

  it("forgets an ack once the condition clears on a reporting pack", () => {
    const acked = ackAlert(NONE, capAt(28));
    const pruned = pruneAcks(acked, [], new Set());
    expect(pruned.size).toBe(0);
    expect(isAcked(pruned, capAt(28))).toBe(false); // the next episode alerts afresh
  });

  // Review focus: a marginal pack that flaps out of range must not re-nag on every
  // reconnect — deriveAlerts drops stale packs, which is not the same as recovering.
  it("keeps the ack while the pack is merely out of range", () => {
    const acked = ackAlert(NONE, capAt(28));
    expect(pruneAcks(acked, [], new Set(["b"]))).toBe(acked);
  });

  it("pruneAcks returns the same map when nothing cleared", () => {
    const a = capAt(28);
    const acked = ackAlert(NONE, a);
    expect(pruneAcks(acked, [a], new Set())).toBe(acked);
    expect(pruneAcks(NONE, [], new Set())).toBe(NONE);
  });
});
