import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import type { FleetItem } from "../../types";
import { groupBases } from "../fleet";
import type { FleetData } from "../useFleetData";
import { HealthView } from "./HealthView";

const NOW = Date.now();
const pack = (address: string, group: string, o: Partial<FleetItem> = {}): FleetItem => ({
  address, group_id: group, alias: `${group} · ${address.slice(-1)}`, ts_ms: NOW,
  soc: 70, current_a: 8, eta_full_min: 192, regen: false, ...o,
});
function render(items: FleetItem[], stale: string[] = [], hero = "2016") {
  const staleAddrs = new Set(stale);
  const data = { items, staleAddrs } as unknown as FleetData;
  const heroBase = groupBases(items, staleAddrs).find((b) => b.id === hero) ?? null;
  return renderToStaticMarkup(<HealthView data={data} heroBase={heroBase} unit="F" mobile={false} />)
    .replace(/<!-- -->/g, "");
}

describe("HealthView time to full", () => {
  it("a charging pack's row and the charging hero base say how long is left", () => {
    const out = render([pack("16A", "2016"), pack("16B", "2016", { eta_full_min: 201 })]);
    expect(out).toContain("≈ 3h 12m left");
    expect(out).toContain("≈ 3h 21m left");
    // The hero base is done when its slower pack is.
    expect(out).toMatch(/Charging · Base 2016 · ≈ 3h 21m to full · ready by/);
  });
  it("a charging pack that stopped reporting shows no countdown", () => {
    const out = render([pack("16A", "2016", { ts_ms: NOW - 600_000 })], ["16A"]);
    expect(out).not.toContain("left");
    expect(out).not.toContain("to full");
  });
  it("nothing for packs that are not charging", () => {
    const out = render([pack("12A", "2012", { current_a: 0, eta_full_min: null })], [], "2012");
    expect(out).not.toContain("left");
    expect(out).not.toContain("to full");
  });
});
