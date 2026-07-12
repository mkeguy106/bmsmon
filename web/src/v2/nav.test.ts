import { describe, expect, it } from "vitest";
import { NAV_GROUPS } from "./nav";

describe("NAV_GROUPS", () => {
  it("has MONITOR + MANAGE groups covering all six views plus a disabled Devices", () => {
    const items = NAV_GROUPS.flatMap((g) => g.items);
    expect(NAV_GROUPS.map((g) => g.label)).toEqual(["MONITOR", "MANAGE"]);
    expect(items.filter((i) => !i.disabled).map((i) => i.view)).toEqual(
      ["command", "health", "journey", "history", "alerts", "settings"],
    );
    expect(items.find((i) => i.disabled)?.label).toBe("Devices");
  });
});
