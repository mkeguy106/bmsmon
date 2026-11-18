import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import type { FleetItem } from "../../types";
import { applyEstimate } from "../../estimate";
import { groupBases } from "../fleet";
import { baseView } from "../model/baseView";
import { selectStageBase } from "../model/stageBase";
import { CommandStage } from "./CommandStage";
import { CommandFleetRail } from "./CommandFleetRail";

const NOW = Date.now();
const SUMMARY = { miles: 0, activeMiles: 0, transitMiles: 0 } as never;
const item = (address: string, group: string, o: Partial<FleetItem>): FleetItem => applyEstimate({
  address, group_id: group, alias: `${group} · ${address.slice(-1)}`, ts_ms: NOW - 1_000,
  soc: 99, remaining_ah: 100, full_charge_ah: 105, current_a: -5, power_w: -64, temp_c: 22,
  regen: false, ...o,
});
const stage = (items: FleetItem[]) => {
  const b = groupBases(items, new Set())[0];
  return renderToStaticMarkup(
    <CommandStage base={b} view={baseView(b, { rangeParams: new Map(), tempConfig: null })}
      tempF={false} distUnit="mi" mobile={false} drivenToday={SUMMARY} />);
};
const withEst = [
  item("2012A", "2012", { est_soc: 92, est_remaining_ah: 96.84, est_source: "counter" }),
  item("2012B", "2012", { est_soc: 60, est_remaining_ah: 63, est_source: "voltage" }),
];
const without = [item("2012A", "2012", {}), item("2012B", "2012", {})];

describe("noEstimateKeepsLayout", () => {
  it("the stage renders the BMS line and the capacity sub-line with and without an estimate", () => {
    const a = stage(withEst), b = stage(without);
    expect((a.match(/BMS \d+%/g) ?? []).length).toBe(2);
    expect((b.match(/BMS \d+%/g) ?? []).length).toBe(2);
    expect(a).toContain("BMS 99%");
    expect(b).toContain("BMS 99%");
    expect((a.match(/BMS \d+\.\d Ah/g) ?? []).length).toBe(2);
    expect((b.match(/BMS \d+\.\d Ah/g) ?? []).length).toBe(2);
    expect(a).toContain("92%");
    expect(a).toContain("~60%");
  });

  it("the rail shows our label then the muted bms number, always", () => {
    const render = (items: FleetItem[]) => {
      const bases = groupBases(items, new Set());
      return renderToStaticMarkup(<CommandFleetRail bases={bases} stageBaseId="2012" onStage={() => {}} />);
    };
    const a = render(withEst), b = render(without);
    expect(a).toContain("~60%");
    expect(a).toContain("bms 99");
    expect((b.match(/bms 99/g) ?? []).length).toBe(2);
  });
});

describe("the stage seize follows our estimate", () => {
  it("seizes on est_soc 60 against a 30 threshold only when the estimate is that low", () => {
    const run = (items: FleetItem[], seizeThreshold: number) => selectStageBase({
      bases: groupBases(items, new Set()), seizeThreshold, pin: null,
      lastDischargeMs: new Map(), sticky: null, nowMs: NOW });
    const low = [item("2016A", "2016", { current_a: 0, est_soc: 60, est_source: "voltage" }),
      item("2016B", "2016", { current_a: 0 }),
      item("2012A", "2012", { current_a: 0 }), item("2012B", "2012", { current_a: 0 })];
    const b = groupBases(low, new Set()).find((x) => x.id === "2016")!;
    expect(b.packs.find((p) => p.item.address === "2016A")!.item.soc).toBe(60);
    expect(run(low, 65)?.baseId).toBe("2016");   // 60 <= 65: seized on our number
    expect(run(low, 30)?.reason).not.toBe("seize"); // the BMS's 99 would never have mattered either way
  });
});
