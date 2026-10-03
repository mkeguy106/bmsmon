import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import type { FleetItem } from "../../types";
import { groupBases } from "../fleet";
import { baseView } from "../model/baseView";
import { CommandRange } from "./CommandRange";
import { CommandStage } from "./CommandStage";

const NOW = Date.now();
const item = (address: "A" | "B", o: Partial<FleetItem>): FleetItem => ({
  address, group_id: "2012", alias: `2012 · ${address}`, ts_ms: NOW - 1_000,
  soc: 60, remaining_ah: 60, current_a: -5, power_w: -64, temp_c: 22, regen: false, ...o,
});
const SUMMARY = { miles: 0, activeMiles: 0, transitMiles: 0 } as never;

describe("Command with a pack lacking capacity", () => {
  const b = groupBases([item("A", {}), item("B", { remaining_ah: null })], new Set())[0];
  const view = baseView(b, { rangeParams: new Map(), tempConfig: null });

  it("range card shows the no-data state and no mileage figure", () => {
    const html = renderToStaticMarkup(<CommandRange view={view} trips={[]} onEditTrips={() => {}} distUnit="mi" />);
    expect(html).toContain("No capacity reading from this base yet");
    expect(html).not.toContain("miles");
  });

  it("stage shows no runtime figure", () => {
    const html = renderToStaticMarkup(
      <CommandStage base={b} view={view} tempF={false} distUnit="mi" mobile={false} drivenToday={SUMMARY} />);
    expect(html).toContain("EST. RUNTIME");
    expect(html).not.toMatch(/~\d+–\d+h/);
  });
});
