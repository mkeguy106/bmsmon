import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import type { FleetItem } from "../../types";
import { groupBases } from "../fleet";
import { CommandAside } from "./CommandAside";

const NOW = Date.now();
const spare = (address: string, o: Partial<FleetItem>): FleetItem => ({
  address, group_id: "2016", alias: `2016 · ${address}`, ts_ms: NOW - 3 * 3_600_000,
  soc: 60, current_a: 8, power_w: 110, eta_full_min: 90, regen: false, ...o,
});
const render = (stale: string[], items: FleetItem[]) => renderToStaticMarkup(
  <CommandAside bases={groupBases(items, new Set(stale))} onOpen={() => {}} todayPoints={[]} />,
).replace(/<!-- -->/g, "");

// Task 6 carry: a spare nobody has seen since its ready time may not be full; the plan gives
// an estimate, never "was due full" (which reads as done).
describe("CommandAside recharge plan for a spare that is not live", () => {
  it("past its ready time: an estimate, never implying it is full", () => {
    const html = render(["A"], [spare("A", {})]);
    expect(html).toContain("est. full");
    expect(html).not.toContain("due full");
  });
  it("before its ready time: an estimate too", () => {
    const html = render(["A"], [spare("A", { ts_ms: NOW - 10 * 60_000 })]);
    expect(html).toContain("est. full");
    expect(html).not.toContain("due full");
  });
  it("a live charging pack still counts down", () => {
    const html = render([], [spare("A", { ts_ms: NOW })]);
    expect(html).toContain("to full · ready by");
  });
});
