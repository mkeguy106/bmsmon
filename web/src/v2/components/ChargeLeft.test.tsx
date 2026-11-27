import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import type { FleetItem } from "../../types";
import { groupBases } from "../fleet";
import { ChargeLeft } from "./ChargeLeft";
import { CommandFleetRail } from "./CommandFleetRail";

const NOW = Date.now();
const MIN = 60_000;
const html = (el: JSX.Element) => renderToStaticMarkup(el).replace(/<!-- -->/g, "");
const pack = (address: string, group: string, o: Partial<FleetItem> = {}): FleetItem => ({
  address, group_id: group, alias: `${group} · ${address.slice(-1)}`, ts_ms: NOW,
  soc: 70, current_a: 8, eta_full_min: 192, regen: false, ...o,
});

describe("ChargeLeft", () => {
  it("counts down to the anchored ready time and names the clock time", () => {
    const out = html(<ChargeLeft readyAtMs={NOW + 192 * MIN + 20_000} />);
    expect(out).toMatch(/^≈ 3h 12m to full · ready by \d{1,2}:\d{2}/);
  });
  it("short form for a table cell", () => {
    expect(html(<ChargeLeft readyAtMs={NOW + 45 * MIN + 20_000} short />)).toBe("≈ 45 min left");
  });
  it("never goes below zero once the ready time has passed", () => {
    expect(html(<ChargeLeft readyAtMs={NOW - 10 * MIN} short />)).toBe("≈ 0 min left");
  });
});

describe("CommandFleetRail", () => {
  const rail = (items: FleetItem[], stale: string[] = []) => html(
    <CommandFleetRail bases={groupBases(items, new Set(stale))} stageBaseId="2012" onStage={() => {}} />);

  it("a charging base says how long it has left", () => {
    const out = rail([pack("16A", "2016"), pack("16B", "2016", { eta_full_min: 201 })]);
    expect(out).toContain("CHARGING");
    expect(out).toMatch(/≈ 3h 2[01]m to full · ready by/);
  });
  it("a base that is not charging says nothing about time to full", () => {
    const out = rail([pack("12A", "2012", { current_a: -4, eta_full_min: null })]);
    expect(out).not.toContain("to full");
  });
  it("a charging base with no estimate yet says nothing either", () => {
    const out = rail([pack("16A", "2016", { eta_full_min: null })]);
    expect(out).toContain("CHARGING");
    expect(out).not.toContain("to full");
  });
});
