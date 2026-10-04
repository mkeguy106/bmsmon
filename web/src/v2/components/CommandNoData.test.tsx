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

// Task 9 carry: the no-data state in km shows no figure either.
describe("Command with a pack lacking capacity, in km", () => {
  const b = groupBases([item("A", {}), item("B", { remaining_ah: null })], new Set())[0];
  const view = baseView(b, { rangeParams: new Map(), tempConfig: null });

  it("range card shows the no-data state and no distance figure", () => {
    const html = renderToStaticMarkup(<CommandRange view={view} trips={[]} onEditTrips={() => {}} distUnit="km" />);
    expect(html).toContain("No capacity reading from this base yet");
    expect(html).not.toContain("km");
    expect(html).not.toMatch(/\d+–\d+/);
  });
});

// Final review (widened Task 9): no range, runtime or mileage figure is rounded up.
describe("Command range figures are floored", () => {
  // 10 Ah × 12.8 V = 128 Wh. Bands chosen so every figure has a fraction ≥ .5:
  // miles 128/20 = 6.4 … 128/13.5 = 9.48; active 128/50 = 2.56 … 128/34 = 3.76 h.
  const rp = new Map([["A", {
    whPerDay: { lo: 78, hi: 182 }, activeW: { lo: 34, hi: 50 }, whPerMile: { lo: 13.5, hi: 20 },
    learnedDays: 5, updatedMs: 0,
  }], ["B", {
    whPerDay: { lo: 78, hi: 182 }, activeW: { lo: 34, hi: 50 }, whPerMile: { lo: 13.5, hi: 20 },
    learnedDays: 5, updatedMs: 0,
  }]]);
  const b = groupBases([item("A", { remaining_ah: 10 }), item("B", { remaining_ah: 10 })], new Set())[0];
  const view = baseView(b, { rangeParams: rp, tempConfig: null });

  it("the range card floors the band, the typical figure and the runtime", () => {
    const html = renderToStaticMarkup(<CommandRange view={view} trips={[]} onEditTrips={() => {}} distUnit="mi" />)
      .replace(/<!-- -->/g, "");
    expect(html).toContain(">6.4–9.4</div>");   // the headline band: 9.48 never shows as 9.5
    expect(html).toContain("~6.4–9.4 mi");      // the detail line too
    expect(html).toContain("typical ~7");        // (6.4 + 9.48) / 2 = 7.94, never 8
    expect(html).toContain("~2–3h use");         // 3.76 h never shows as 4h
  });

  it("the stage's runtime tile floors the high end", () => {
    const html = renderToStaticMarkup(
      <CommandStage base={b} view={view} tempF={false} distUnit="mi" mobile={false} drivenToday={SUMMARY} />)
      .replace(/<!-- -->/g, "");
    expect(html).toContain("~2–3h");
    expect(html).not.toContain("~3–4h");
  });
});
