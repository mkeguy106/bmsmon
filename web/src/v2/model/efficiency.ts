// Journey efficiency: the viewed outing's real cost-per-mile, compared to the learned
// whPerMile band, plus a live "how far will the remaining charge take me" projection.
// Energy basis is BASE-TOTAL throughout: the merged base track (mergeBaseTracks) sums pack
// power, the band sums every pack's whPerMile, and the remaining energy is the base's usable
// energy from the base view-model (baseView.usableWh: pack count × the weaker pack's
// remaining Ah × nominal V). The pair is in series, so summing both packs' remaining Wh
// overstated it (WEB-15).
// The merged track holds only the packs that sent GPS samples in each bucket, so a bucket
// short of a pack is scaled up to the whole base (series: one current through both packs),
// and "miles left at today's rate" is shown only while every pack is live and the track
// covers every pack: the remaining energy spans every pack, and a rate from part of the base
// read up to 2× high (final review C1).
// Design: docs/superpowers/specs/2026-07-16-journey-efficiency-card-design.md
import type { TrackPoint } from "../track";
import { DISCHARGE_EPS } from "./journey";
import type { RangeBand, RangeParams } from "../../range";
import type { BaseView } from "./baseView";

/** Learner's own outing gate — below this the per-mile cost is noise. */
export const MIN_OUTING_MI = 0.5;
/** Cap a single bucket's Δt so a disconnect/idle gap can't inflate integrated Wh. */
const MAX_BUCKET_DT_H = 60 / 3600; // 60 s

/** Chair energy over the merged base track: integrate |power| across discharging buckets.
 *  With [packCount] (the base's size), a bucket that fewer packs reported in is scaled up to
 *  the whole base: the pair is in series, so a missing pack drew what the reporting ones did. */
export function outingWh(points: TrackPoint[], packCount?: number): number {
  let wh = 0;
  for (let i = 1; i < points.length; i++) {
    const pt = points[i];
    if ((pt.current_a ?? 0) < -DISCHARGE_EPS) {
      const dtH = Math.min((pt.t - points[i - 1].t) / 3_600_000, MAX_BUCKET_DT_H);
      const scale = packCount != null && pt.packs != null && pt.packs > 0 && pt.packs < packCount
        ? packCount / pt.packs : 1;
      if (dtH > 0) wh += Math.abs(pt.power_w ?? 0) * dtH * scale;
    }
  }
  return wh;
}

/** The merged track has every pack of the base: some bucket holds all [packCount] of them.
 *  False when a pack's track failed to load or the pack never reported in the window. */
export function trackCoversEveryPack(points: TrackPoint[], packCount: number): boolean {
  return packCount > 0 && points.some((pt) => (pt.packs ?? 0) >= packCount);
}

/** First→last SOC drop over the track (merged points carry the weaker pack's min SOC).
 *  Null when unknown or non-positive (flat / charging over the window). */
export function drainedPct(points: TrackPoint[]): number | null {
  const withSoc = points.filter((p) => p.soc != null);
  if (withSoc.length < 2) return null;
  const d = withSoc[0].soc! - withSoc[withSoc.length - 1].soc!;
  return d > 0 ? d : null;
}

/** Sum each pack's whPerMile band → base-total band (same basis as the merged track's
 *  summed power). Invalid/non-positive bands are dropped; null if none survive. */
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

/** Why a live projection shows no "today's rate" figure. */
export type TodayRateGap =
  /** A pack of the base is not live: its share of the cost is missing from the track. */
  | "pack-not-live"
  /** Every pack is live, but the track is missing a pack (its fetch failed, or no samples). */
  | "track-incomplete";

export interface EfficiencySummary {
  wh: number;
  activeMiles: number;
  /** Base-total Wh per active mile; null when the outing is too short to gauge. */
  costPerMile: number | null;
  drainedPct: number | null;
  band: RangeBand | null;
  status: BandStatus | null;
  /** Any pack still on the seed band → the comparison is a seed estimate. */
  seed: boolean;
  /** Live-only projections from remaining charge (null on past days / while charging). */
  milesAtTodayRate: number | null;
  milesAtUsualRate: number | null;
  /** Set when a live projection withholds today's rate (and why); null otherwise. */
  todayRateWithheld: TodayRateGap | null;
}

export interface EfficiencyInput {
  points: TrackPoint[];        // cleaned, merged base track
  activeMiles: number;         // summary.activeMiles (chair-driven, excludes transit)
  packParams: RangeParams[];   // one per pack in the base (baseView.packParams)
  usableWh: number | null;     // usable base energy (baseView.usableWh); null = unknown
  charging: boolean;
  live: boolean;
  /** How many packs the base has (baseView.packs). */
  packCount: number;
  /** Every pack of the base is live (everyPackLive). */
  everyPackLive: boolean;
}

export function efficiencySummary(input: EfficiencyInput): EfficiencySummary {
  const { points, activeMiles, packParams, usableWh, charging, live, packCount, everyPackLive } = input;

  const wh = outingWh(points, packCount);
  const costPerMile = activeMiles >= MIN_OUTING_MI && wh > 0 ? wh / activeMiles : null;
  const band = baseBand(packParams);
  const status = costPerMile != null && band != null ? bandStatus(costPerMile, band) : null;
  const seed = packParams.some((p) => p.learnedDays === 0);

  // Projection only live and not charging. Zero usable energy is real (an empty base
  // projects 0 miles); unknown energy projects nothing.
  const remWh = usableWh != null && Number.isFinite(usableWh) && usableWh >= 0 ? usableWh : null;
  const usualMid = band != null ? (band.lo + band.hi) / 2 : null;
  const project = live && !charging && remWh != null;
  // Today's rate only from a cost that spans the same packs as the remaining energy.
  const todayRateWithheld: TodayRateGap | null = !project || costPerMile == null ? null
    : !everyPackLive ? "pack-not-live"
      : !trackCoversEveryPack(points, packCount) ? "track-incomplete"
        : null;

  return {
    wh,
    activeMiles,
    costPerMile,
    drainedPct: drainedPct(points),
    band,
    status,
    seed,
    milesAtTodayRate: project && todayRateWithheld == null && remWh != null && costPerMile != null
      && costPerMile > 0 ? remWh / costPerMile : null,
    milesAtUsualRate: project && remWh != null && usualMid != null && usualMid > 0
      ? remWh / usualMid : null,
    todayRateWithheld,
  };
}

/** Every pack of the base is live (none is last known). */
export const everyPackLive = (view: Pick<BaseView, "packs" | "livePacks"> | null): boolean =>
  view != null && view.packs.length > 0 && view.livePacks.length === view.packs.length;

/** Whether Journey may project miles left: the window must be live AND at least one pack in
 *  the base live, so an offline base shows no projection (matching Command). */
export const projectionLive = (windowIsLive: boolean, view: Pick<BaseView, "livePacks"> | null): boolean =>
  windowIsLive && (view?.livePacks.length ?? 0) > 0;
