import { describe, expect, it } from "vitest";
import type { GuestStatus } from "./feed";
import { guestCap, guestFlow } from "./dock";

const status = (over: Partial<GuestStatus>): GuestStatus => ({
  ts: 0, soc: 97, packs: [{ label: "A", soc: 98 }, { label: "B", soc: 97 }],
  current_a: 0, power_w: 0, regen: false, ...over,
});

describe("guestCap", () => {
  it("null status renders empty", () => {
    expect(guestCap(null)).toEqual({ pct: null, detail: "", band: "ok" });
  });

  it("pair detail + alert bands", () => {
    expect(guestCap(status({}))).toEqual({ pct: 97, detail: "A98·B97", band: "ok" });
    expect(guestCap(status({ soc: 30 })).band).toBe("warn");
    expect(guestCap(status({ soc: 15 })).band).toBe("crit");
    expect(guestCap(status({ soc: 31 })).band).toBe("ok");
  });

  it("single pack has no detail", () => {
    expect(guestCap(status({ packs: [{ label: "A", soc: 88 }], soc: 88 })).detail).toBe("");
  });
});

describe("guestFlow", () => {
  it("null status is idle", () => {
    expect(guestFlow(null)).toEqual({ kind: "idle", watts: 0, frac: 0 });
  });

  it("discharge is OUT with 600W full scale", () => {
    const f = guestFlow(status({ current_a: -7.5, power_w: -95.0 }));
    expect(f).toEqual({ kind: "out", watts: 95, frac: 95 / 600 });
  });

  it("charge vs regen by flag; frac caps at 1", () => {
    expect(guestFlow(status({ current_a: 20, power_w: 700 }))).toEqual(
      { kind: "chg", watts: 700, frac: 1 });
    expect(guestFlow(status({ current_a: 5, power_w: 60, regen: true })).kind).toBe("regen");
  });

  it("sub-epsilon current is idle", () => {
    expect(guestFlow(status({ current_a: 0.05, power_w: 3 }))).toEqual(
      { kind: "idle", watts: 0, frac: 0 });
  });
});
