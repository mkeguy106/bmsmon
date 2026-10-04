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
    expect(c).toEqual({ pct: 68, detail: "A69·B68", band: "ok", lastKnown: false, offline: false });
  });
  it("bands: warn at <=30, crit at <=15", () => {
    expect(dockCapacity([pack("A", { soc: 24 })]).band).toBe("warn");
    expect(dockCapacity([pack("A", { soc: 12 })]).band).toBe("crit");
  });
  it("bands from raw soc before rounding: 30.4 rounds to pct 30 but bands ok (raw > 30)", () => {
    const c = dockCapacity([pack("A", { soc: 30.4 })]);
    expect(c.pct).toBe(30);
    expect(c.band).toBe("ok");
  });
  it("a pack with no SOC reading leaves CAP unbounded: dash, never the other pack number", () => {
    const c = dockCapacity([pack("A", { soc: 69 }), pack("B", { soc: null }, false)]);
    expect(c.pct).toBeNull();
    expect(c.detail).toBe("A69·B—");
    expect(c.lastKnown).toBe(false);
  });
  it("a disconnected weaker pack bounds pct and is flagged (WEB-14)", () => {
    const c = dockCapacity([pack("A", { soc: 14 }, false), pack("B", { soc: 55 })]);
    expect(c).toEqual({ pct: 14, detail: "A14·B55", band: "crit", lastKnown: true, offline: false });
  });
  it("any non-live pack raises the flag, even at a tie or when it is not the weakest", () => {
    expect(dockCapacity([pack("A", { soc: 40 }, false), pack("B", { soc: 40 })]).lastKnown).toBe(true);
    const c = dockCapacity([pack("A", { soc: 60 }, false), pack("B", { soc: 30 })]);
    expect(c.pct).toBe(30);
    expect(c.lastKnown).toBe(true);
  });
  it("all packs live: no flag", () => {
    expect(dockCapacity([pack("A", { soc: 60 }), pack("B", { soc: 30 })]).lastKnown).toBe(false);
  });
  it("single pack: no detail suffix", () => {
    expect(dockCapacity([pack("A", { soc: 42 })]).detail).toBe("");
  });
  it("a lone last-known pack still reads, flagged", () => {
    const c = dockCapacity([pack("A", { soc: 69 }, false)]);
    expect(c.pct).toBe(69);
    expect(c.lastKnown).toBe(true);
  });
  it("no reading anywhere: pct null", () => {
    expect(dockCapacity([pack("A", { soc: null }), pack("B", { soc: null }, false)]).pct).toBeNull();
  });
});

describe("dockFlow", () => {
  it("discharging pair sums to OUT with fraction of 600 W", () => {
    const f = dockFlow([
      pack("A", { current_a: -3.1, power_w: 77 }),
      pack("B", { current_a: -3.0, power_w: 77 }),
    ]);
    expect(f!.kind).toBe("out");
    expect(f!.watts).toBe(154);
    expect(f!.frac).toBeCloseTo(154 / PAIR_FLOW_FULL_W, 5);
  });
  it("charging reads CHG; regen flag flips it to REGEN", () => {
    expect(dockFlow([pack("A", { current_a: 4, power_w: 105 })])!.kind).toBe("chg");
    expect(dockFlow([pack("A", { current_a: 4, power_w: 105, regen: true })])!.kind).toBe("regen");
  });
  it("idle inside the ±0.1 A deadband", () => {
    expect(dockFlow([pack("A", { current_a: 0.05, power_w: 1 })])).toEqual({ kind: "idle", watts: 0, frac: 0 });
  });
  it("fraction clamps at 1 on hard pulls", () => {
    expect(dockFlow([pack("A", { current_a: -60, power_w: 882 })])!.frac).toBe(1);
  });
  it("disconnected packs are excluded from the sums", () => {
    const f = dockFlow([pack("A", { current_a: -3, power_w: 80 }), pack("B", { current_a: -3, power_w: 80 }, false)]);
    expect(f!.watts).toBe(80);
  });
  // Final review I2: with no live pack there is no flow to report; "0 W IDLE" read as a live,
  // idle base.
  it("no live pack: no flow at all, never a zero-watt IDLE", () => {
    expect(dockFlow([pack("A", { current_a: -3, power_w: 80 }, false), pack("B", {}, false)])).toBeNull();
    expect(dockFlow([])).toBeNull();
  });
});

// Final review I2: an all-stale base keeps its last-known bound ("≤NN%"), flagged offline so
// the dock renders it muted, never in live alert colours.
describe("dockCapacity with no live pack", () => {
  it("keeps the last-known bound and flags the base offline", () => {
    const c = dockCapacity([pack("A", { soc: 42 }, false), pack("B", { soc: 55 }, false)]);
    expect(c).toMatchObject({ pct: 42, lastKnown: true, offline: true });
  });
  it("one live pack is not offline", () => {
    expect(dockCapacity([pack("A", { soc: 42 }), pack("B", { soc: 55 }, false)]).offline).toBe(false);
  });
});
