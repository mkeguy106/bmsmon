import { describe, expect, it } from "vitest";
import type { BasePack } from "../fleet";
import type { FleetItem } from "../../types";
import { socColor } from "./journey";
import { PAIR_FLOW_FULL_W, dockCapacity, dockFlow } from "./dock";

const pack = (letter: string, over: Partial<FleetItem>, connected = true): BasePack => ({
  letter, connected,
  item: { address: letter, ts_ms: 0, soc: 68, current_a: 0, power_w: 0, regen: false, ...over } as FleetItem,
});

describe("socColor", () => {
  it("follows the alert bands", () => {
    expect(socColor(68)).toBe("var(--ok)");
    expect(socColor(31)).toBe("var(--ok)");
    expect(socColor(30)).toBe("var(--warn)");
    expect(socColor(16)).toBe("var(--warn)");
    expect(socColor(15)).toBe("var(--live)");
    expect(socColor(null)).toBe("var(--text-4)");
  });
});

describe("dockCapacity", () => {
  it("pair pct is the weaker pack; detail lists both", () => {
    const c = dockCapacity([pack("A", { soc: 69 }), pack("B", { soc: 68 })]);
    expect(c).toEqual({ pct: 68, detail: "A69·B68", band: "ok" });
  });
  it("bands: warn at <=30, crit at <=15", () => {
    expect(dockCapacity([pack("A", { soc: 24 })]).band).toBe("warn");
    expect(dockCapacity([pack("A", { soc: 12 })]).band).toBe("crit");
  });
  it("disconnected packs don't drive pct but still show in detail with a dash", () => {
    const c = dockCapacity([pack("A", { soc: 69 }), pack("B", { soc: null }, false)]);
    expect(c.pct).toBe(69);
    expect(c.detail).toBe("A69·B—");
  });
  it("single pack: no detail suffix", () => {
    expect(dockCapacity([pack("A", { soc: 42 })]).detail).toBe("");
  });
  it("nothing reachable: pct null", () => {
    expect(dockCapacity([pack("A", { soc: 69 }, false)]).pct).toBeNull();
  });
});

describe("dockFlow", () => {
  it("discharging pair sums to OUT with fraction of 600 W", () => {
    const f = dockFlow([
      pack("A", { current_a: -3.1, power_w: 77 }),
      pack("B", { current_a: -3.0, power_w: 77 }),
    ]);
    expect(f.kind).toBe("out");
    expect(f.watts).toBe(154);
    expect(f.frac).toBeCloseTo(154 / PAIR_FLOW_FULL_W, 5);
  });
  it("charging reads CHG; regen flag flips it to REGEN", () => {
    expect(dockFlow([pack("A", { current_a: 4, power_w: 105 })]).kind).toBe("chg");
    expect(dockFlow([pack("A", { current_a: 4, power_w: 105, regen: true })]).kind).toBe("regen");
  });
  it("idle inside the ±0.1 A deadband", () => {
    expect(dockFlow([pack("A", { current_a: 0.05, power_w: 1 })])).toEqual({ kind: "idle", watts: 0, frac: 0 });
  });
  it("fraction clamps at 1 on hard pulls", () => {
    expect(dockFlow([pack("A", { current_a: -60, power_w: 882 })]).frac).toBe(1);
  });
  it("disconnected packs are excluded from the sums", () => {
    const f = dockFlow([pack("A", { current_a: -3, power_w: 80 }), pack("B", { current_a: -3, power_w: 80 }, false)]);
    expect(f.watts).toBe(80);
  });
});
