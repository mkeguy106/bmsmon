// Journey efficiency: the viewed outing's real cost-per-mile, compared to the learned
// whPerMile band, plus a live "how far will the remaining charge take me" projection.
// Energy basis is BASE-TOTAL throughout — the merged base track (mergeBaseTracks) sums
// pack power, so remaining Wh and the band are summed across the connected packs to match.
// Design: docs/superpowers/specs/2026-07-16-journey-efficiency-card-design.md
import type { TrackPoint } from "../track";
import { DISCHARGE_EPS } from "./journey";
import { NOMINAL_PACK_V, type RangeBand, type RangeParams } from "../../range";

/** Learner's own outing gate — below this the per-mile cost is noise. */
export const MIN_OUTING_MI = 0.5;
/** Cap a single bucket's Δt so a disconnect/idle gap can't inflate integrated Wh. */
const MAX_BUCKET_DT_H = 60 / 3600; // 60 s

/** Chair energy over the merged base track: integrate |power| across discharging buckets. */
export function outingWh(points: TrackPoint[]): number {
  let wh = 0;
  for (let i = 1; i < points.length; i++) {
    if ((points[i].current_a ?? 0) < -DISCHARGE_EPS) {
      const dtH = Math.min((points[i].t - points[i - 1].t) / 3_600_000, MAX_BUCKET_DT_H);
      if (dtH > 0) wh += Math.abs(points[i].power_w ?? 0) * dtH;
    }
  }
  return wh;
}

/** First→last SOC drop over the track (merged points carry the weaker pack's min SOC).
 *  Null when unknown or non-positive (flat / charging over the window). */
export function drainedPct(points: TrackPoint[]): number | null {
  const withSoc = points.filter((p) => p.soc != null);
  if (withSoc.length < 2) return null;
  const d = withSoc[0].soc! - withSoc[withSoc.length - 1].soc!;
  return d > 0 ? d : null;
}

/** Sum each connected pack's whPerMile band → base-total band (same basis as the merged
 *  track's summed power). Invalid/non-positive bands are dropped; null if none survive. */
export function baseBand(packParams: RangeParams[]): RangeBand | null {
  const bands = packParams
    .map((p) => p.whPerMile)
    .filter((b) => Number.isFinite(b.lo) && Number.isFinite(b.hi) && b.lo > 0 && b.hi > 0);
  if (bands.length === 0) return null;
  return {
    lo: bands.reduce((a, b) => a + b.lo, 0),
    hi: bands.reduce((a, b) => a + b.hi, 0),
  };
}

export type BandStatus = "below" | "inside" | "above";
/** Where the outing's cost sits vs the band. below = cheaper than usual (better). */
export function bandStatus(costPerMile: number, band: RangeBand): BandStatus {
  if (costPerMile < band.lo) return "below";
  if (costPerMile > band.hi) return "above";
  return "inside";
}

export interface EfficiencySummary {
  wh: number;
  activeMiles: number;
  /** Base-total Wh per active mile; null when the outing is too short to gauge. */
  costPerMile: number | null;
  drainedPct: number | null;
  band: RangeBand | null;
  status: BandStatus | null;
  /** Any connected pack still on the seed band → the comparison is a seed estimate. */
  seed: boolean;
  /** Live-only projections from remaining charge (null on past days / while charging). */
  milesAtTodayRate: number | null;
  milesAtUsualRate: number | null;
}

export interface EfficiencyInput {
  points: TrackPoint[];        // cleaned, merged base track
  activeMiles: number;         // summary.activeMiles (chair-driven, excludes transit)
  packParams: RangeParams[];   // one per connected pack
  remainingAh: number[];       // connected packs' live remaining_ah (finite, > 0)
  charging: boolean;
  live: boolean;
}

export function efficiencySummary(input: EfficiencyInput): EfficiencySummary {
  const { points, activeMiles, packParams, remainingAh, charging, live } = input;

  const wh = outingWh(points);
  const costPerMile = activeMiles >= MIN_OUTING_MI && wh > 0 ? wh / activeMiles : null;
  const band = baseBand(packParams);
  const status = costPerMile != null && band != null ? bandStatus(costPerMile, band) : null;
  const seed = packParams.some((p) => p.learnedDays === 0);

  // Base-total remaining Wh (sum of connected packs). Projection only live & discharging.
  const baseRemWh = remainingAh
    .filter((ah) => Number.isFinite(ah) && ah > 0)
    .reduce((a, ah) => a + ah * NOMINAL_PACK_V, 0);
  const usualMid = band != null ? (band.lo + band.hi) / 2 : null;
  const project = live && !charging && baseRemWh > 0;

  return {
    wh,
    activeMiles,
    costPerMile,
    drainedPct: drainedPct(points),
    band,
    status,
    seed,
    milesAtTodayRate: project && costPerMile != null && costPerMile > 0 ? baseRemWh / costPerMile : null,
    milesAtUsualRate: project && usualMid != null && usualMid > 0 ? baseRemWh / usualMid : null,
  };
}
