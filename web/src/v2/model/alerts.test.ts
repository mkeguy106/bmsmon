import { describe, expect, it } from "vitest";
import { deriveAlerts } from "./alerts";
import type { FleetItem } from "../../types";

const mk = (o: Partial<FleetItem>): FleetItem => ({ address: "x", ts_ms: 100, ...o });

describe("deriveAlerts", () => {
  it("fires a warning capacity alert at the crossed rung", () => {
    const a = deriveAlerts([mk({ address: "a", soc: 22 })], new Set(), null);
    const cap = a.find((x) => x.kind === "capacity")!;
    expect(cap.severity).toBe("warning");   // 22 → rung 20, not ≤15
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
