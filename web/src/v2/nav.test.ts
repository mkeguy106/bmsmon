import { describe, expect, it } from "vitest";
import { NAV_GROUPS } from "./nav";

describe("NAV_GROUPS", () => {
  it("has MONITOR + MANAGE groups covering all six views (Devices now lives inside Settings)", () => {
    const items = NAV_GROUPS.flatMap((g) => g.items);
    expect(NAV_GROUPS.map((g) => g.label)).toEqual(["MONITOR", "MANAGE"]);
    expect(items.filter((i) => !i.disabled).map((i) => i.view)).toEqual(
      ["command", "health", "journey", "history", "alerts", "settings"],
    );
    expect(items.some((i) => i.disabled)).toBe(false);
    expect(items.some((i) => i.label === "Devices")).toBe(false);
  });
});
