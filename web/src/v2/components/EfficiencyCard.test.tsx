import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import type { EfficiencySummary } from "../model/efficiency";
import { EfficiencyCard } from "./EfficiencyCard";

const summary = (o: Partial<EfficiencySummary>): EfficiencySummary => ({
  wh: 120, activeMiles: 2, costPerMile: 60, drainedPct: 4, band: { lo: 100, hi: 170 },
  status: "below", seed: false, milesAtTodayRate: 21.3, milesAtUsualRate: 9.4,
  todayRateWithheld: null, ...o,
});
// Server rendering separates adjacent text with "<!-- -->"; drop it to read the text.
const render = (s: EfficiencySummary) =>
  renderToStaticMarkup(<EfficiencyCard summary={s} live charging={false} distUnit="mi" />)
    .replace(/<!-- -->/g, "");

describe("EfficiencyCard live projection", () => {
  it("shows today's rate and the usual rate when every pack is live and in the track", () => {
    const html = render(summary({}));
    expect(html).toContain("left at today’s rate");
    expect(html).toContain("at your usual");
  });

  // Final review C1: no today's-rate figure unless every pack is live and in the track; the
  // band-based "at your usual" stays, and the card says why today's rate is missing.
  it("a pack that is not live: no today's-rate figure, the usual rate stays, with the reason", () => {
    const html = render(summary({ milesAtTodayRate: null, todayRateWithheld: "pack-not-live" }));
    expect(html).not.toContain("today’s rate</");
    expect(html).not.toContain("21");
    expect(html).toContain("left at your usual rate");
    expect(html).toContain("Today’s rate needs every pack reporting live");
  });

  it("a track missing a pack: no today's-rate figure, with the reason", () => {
    const html = render(summary({ milesAtTodayRate: null, todayRateWithheld: "track-incomplete" }));
    expect(html).not.toContain("21");
    expect(html).toContain("left at your usual rate");
    expect(html).toContain("Today’s rate needs every pack’s track");
  });
});

// Final review (widened Task 9): the "left at" projections are floored, never rounded up.
describe("EfficiencyCard projections are floored", () => {
  it("whole miles at 10 or more, tenths below, both floored", () => {
    const html = render(summary({ milesAtTodayRate: 21.7, milesAtUsualRate: 9.96 }));
    expect(html).toContain("21 mi");
    expect(html).not.toContain("22 mi");
    expect(html).toContain("~9.9 at your usual");
  });
});
