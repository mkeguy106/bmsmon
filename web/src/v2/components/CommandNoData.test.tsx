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
    expect(html).not.toMatch(/\d–\d/);
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

// Task 5 carry: the other render states — a stale pack lacking capacity, charging, and an
// estimate that includes a last-known pack.
const strip = (html: string) => html.replace(/<!-- -->/g, "");
const rangeHtml = (v: ReturnType<typeof baseView>, unit: "mi" | "km" = "mi") =>
  strip(renderToStaticMarkup(<CommandRange view={v} trips={[]} onEditTrips={() => {}} distUnit={unit} />));
const stageHtml = (b: ReturnType<typeof groupBases>[number], v: ReturnType<typeof baseView>) =>
  strip(renderToStaticMarkup(
    <CommandStage base={b} view={v} tempF={false} distUnit="mi" mobile={false} drivenToday={SUMMARY} />));

describe("Command with a STALE pack lacking capacity", () => {
  const b = groupBases([item("A", {}), item("B", { remaining_ah: null, ts_ms: NOW - 600_000 })], new Set(["B"]))[0];
  const view = baseView(b, { rangeParams: new Map(), tempConfig: null });

  it("no figure anywhere: the stale pack could be the weaker one", () => {
    expect(rangeHtml(view)).toContain("No capacity reading from this base yet");
    expect(rangeHtml(view)).not.toMatch(/\d–\d/);
    expect(stageHtml(b, view)).not.toMatch(/~\d+(\.\d)?–\d+(\.\d)?h/);
  });
});

describe("Command while charging", () => {
  it("the range card yields to the recharge plan; the stage shows time to full", () => {
    const b = groupBases([item("A", { current_a: 8, power_w: 110, eta_full_min: 90 }),
      item("B", { current_a: 8, power_w: 110, eta_full_min: 60 })], new Set())[0];
    const view = baseView(b, { rangeParams: new Map(), tempConfig: null });
    expect(rangeHtml(view)).toContain("Charging — see recharge plan");
    const html = stageHtml(b, view);
    expect(html).toContain("TIME TO FULL");
    expect(html).toContain("1h 30m");
    expect(html).toContain("CHARGE IN");
    expect(html).not.toContain("partial");
  });

  // Task 5 carry: a stale pack's own time to full is not in the figure.
  it("flags the time to full as partial while a pack is not live", () => {
    const b = groupBases([item("A", { current_a: 8, power_w: 110, eta_full_min: 90 }),
      item("B", { ts_ms: NOW - 600_000 })], new Set(["B"]))[0];
    const view = baseView(b, { rangeParams: new Map(), tempConfig: null });
    const html = stageHtml(b, view);
    expect(html).toContain("1h 30m");
    expect(html).toMatch(/partial · excl\. B · last seen/);
  });
});

describe("Command estimate with a last-known pack", () => {
  const b = groupBases([item("A", { remaining_ah: 50 }),
    item("B", { remaining_ah: 20, ts_ms: NOW - 600_000 })], new Set(["B"]))[0];
  const view = baseView(b, { rangeParams: new Map(), tempConfig: null });

  it("the range card shows the bound and names the last-known pack", () => {
    const html = rangeHtml(view);
    expect(html).toContain("miles · typical");
    expect(html).toContain("Includes pack B&#x27;s last-known reading");
  });

  it("the stage's runtime names it too", () => {
    const html = stageHtml(b, view);
    expect(html).toMatch(/~\d+(\.\d)?–\d+(\.\d)?h/);
    expect(html).toContain("incl. B · last seen");
  });
});

// Task 5 carry: the flow label follows the view, so a regen burst is not labelled as a draw
// or a charge.
describe("Command flow label during regen", () => {
  it("reads REGEN IN", () => {
    const b = groupBases([item("A", { current_a: 6, power_w: 80, regen: true }),
      item("B", { current_a: 6, power_w: 80, regen: true })], new Set())[0];
    const view = baseView(b, { rangeParams: new Map(), tempConfig: null });
    const html = stageHtml(b, view);
    expect(html).toContain("REGEN IN");
    expect(html).not.toContain("DRAW NOW");
    expect(html).not.toContain("CHARGE IN");
  });
});
