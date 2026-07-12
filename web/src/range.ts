// Discharge-remaining estimate — line-for-line TypeScript twin of the Android pure formula
// (android/.../model/RangeEstimate.kt). Keep the math identical; both test suites share the
// same vectors. Documented divergence: the web shows the history band without the live tilt
// (the tilt inputs live in the phone's Room DB). Design:
// docs/superpowers/specs/2026-07-11-discharge-estimate-design.md

export const NOMINAL_PACK_V = 12.8;

export interface RangeBand { lo: number; hi: number }
export interface RangeParams {
  whPerDay: RangeBand; activeW: RangeBand; whPerMile: RangeBand;
  learnedDays: number; updatedMs: number;
}

/** Cold-start bands — must match SEED_RANGE_PARAMS in RangeEstimate.kt. */
export const SEED_RANGE_PARAMS: RangeParams = {
  whPerDay: { lo: 78, hi: 182 },
  activeW: { lo: 52.5, hi: 97.5 },
  whPerMile: { lo: 15, hi: 25 },
  learnedDays: 0, updatedMs: 0,
};

/** One row of GET /web/range-config. */
export interface RangeConfigRow {
  device_id: string; address: string;
  wh_per_day_lo: number; wh_per_day_hi: number;
  active_w_lo: number; active_w_hi: number;
  wh_per_mile_lo: number; wh_per_mile_hi: number;
  learned_days: number; updated_at_ms: number;
}

/** Newest row per address → params map (rows arrive newest-first, but don't rely on it). */
export function selectRangeParams(rows: RangeConfigRow[]): Map<string, RangeParams> {
  const best = new Map<string, RangeConfigRow>();
  for (const r of rows) {
    const cur = best.get(r.address);
    if (!cur || r.updated_at_ms > cur.updated_at_ms) best.set(r.address, r);
  }
  const out = new Map<string, RangeParams>();
  for (const [addr, r] of best) {
    out.set(addr, {
      whPerDay: { lo: r.wh_per_day_lo, hi: r.wh_per_day_hi },
      activeW: { lo: r.active_w_lo, hi: r.active_w_hi },
      whPerMile: { lo: r.wh_per_mile_lo, hi: r.wh_per_mile_hi },
      learnedDays: r.learned_days, updatedMs: r.updated_at_ms,
    });
  }
  return out;
}

export interface PackRange {
  milesLo: number; milesHi: number;
  activeHLo: number; activeHHi: number;
  wallHLo: number; wallHHi: number;
}

/** Per-pack estimate, or null when charging (the recharge ETA owns the slot) / no capacity. */
export function estimatePackRange(
  charging: boolean,
  remainingAh: number | null | undefined,
  params: RangeParams,
): PackRange | null {
  if (charging) return null;
  if (remainingAh == null || !Number.isFinite(remainingAh) || remainingAh <= 0) return null;
  // A band edge that is zero/negative/non-finite would divide to Infinity — no estimate
  // beats a nonsense one (shields against stale synced rows).
  const bands = [params.whPerDay, params.activeW, params.whPerMile];
  if (bands.some((b) => !Number.isFinite(b.lo) || !Number.isFinite(b.hi) || b.lo <= 0 || b.hi <= 0)) return null;
  const remWh = remainingAh * NOMINAL_PACK_V;
  return {
    milesLo: remWh / params.whPerMile.hi, milesHi: remWh / params.whPerMile.lo,
    activeHLo: remWh / params.activeW.hi, activeHHi: remWh / params.activeW.lo,
    wallHLo: (remWh / params.whPerDay.hi) * 24, wallHHi: (remWh / params.whPerDay.lo) * 24,
  };
}

/** Base-level readout: the weaker pack bounds each figure (series pair — it ends the trip). */
export function minRange(ranges: PackRange[]): PackRange {
  return ranges.reduce((a, b) => ({
    milesLo: Math.min(a.milesLo, b.milesLo), milesHi: Math.min(a.milesHi, b.milesHi),
    activeHLo: Math.min(a.activeHLo, b.activeHLo), activeHHi: Math.min(a.activeHHi, b.activeHHi),
    wallHLo: Math.min(a.wallHLo, b.wallHLo), wallHHi: Math.min(a.wallHHi, b.wallHHi),
  }));
}

/** "~37–50 mi · ~9–13h use · ~5–9 days" (days when the low bound exceeds 48 h, else hours). */
export function formatRangeLine(r: PackRange): string {
  const miles = r.milesHi < 10
    ? `~${r.milesLo.toFixed(1)}–${r.milesHi.toFixed(1)} mi`
    : `~${Math.round(r.milesLo)}–${Math.round(r.milesHi)} mi`;
  const use = `~${Math.round(r.activeHLo)}–${Math.round(r.activeHHi)}h use`;
  const wall = r.wallHLo > 48
    ? `~${Math.round(r.wallHLo / 24)}–${Math.round(r.wallHHi / 24)} days`
    : `~${Math.round(r.wallHLo)}–${Math.round(r.wallHHi)}h`;
  return `${miles} · ${use} · ${wall}`;
}
