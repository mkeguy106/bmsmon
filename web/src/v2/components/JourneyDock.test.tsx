import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import type { BasePack } from "../fleet";
import type { FleetItem } from "../../types";
import { JourneyDock } from "./JourneyDock";

const pack = (letter: string, soc: number, connected: boolean): BasePack => ({
  letter, connected,
  item: { address: letter, ts_ms: 0, soc, current_a: 0, power_w: 0, regen: false } as FleetItem,
});
const SUMMARY = { miles: 1, activeMiles: 1, transitMiles: 0, peakW: 123, durationMin: 30 };
const render = (packs: BasePack[]) =>
  renderToStaticMarkup(<JourneyDock summary={SUMMARY} packs={packs} distUnit="mi" />);

// Final review I2: a base with no live pack read like a live, idle, healthy base (a green
// "≤42%" bar and "0 W IDLE").
describe("JourneyDock with no live pack", () => {
  const html = render([pack("A", 42, false), pack("B", 55, false)]);

  it("CAP keeps the last-known bound, muted, never in a live alert colour", () => {
    expect(html).toContain("≤42%");
    expect(html).toContain("background:var(--text-4)");
    expect(html).not.toContain("var(--ok)");
    expect(html).not.toContain("var(--warn)");
    expect(html).not.toContain("var(--live)");
  });

  it("FLOW reads \"—\", not 0 W IDLE", () => {
    expect(html).not.toContain("0 W");
    expect(html).not.toContain("IDLE");
    expect(html).toMatch(/FLOW<\/span>.*—/);
  });
});

describe("JourneyDock with a live pack", () => {
  it("CAP keeps its alert colour and FLOW its reading", () => {
    const html = render([pack("A", 42, true), pack("B", 55, true)]);
    expect(html).toContain("background:var(--ok)");
    expect(html).toContain("0 W");
    expect(html).toContain("IDLE");
  });
});
