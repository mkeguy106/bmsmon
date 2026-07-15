// Pure helpers for the History view's [from,to] fetch window.
//
// The trends fetch key includes the window bounds, so how often "now" is
// re-quantized IS the refetch cadence. The 24H window renders live-edge data
// worth refreshing every minute; every longer window (7D/1M/1Y/ALL/custom)
// is served in ≥30-min buckets that cannot change minute-to-minute, so a
// coarser quantum avoids refetching all trend series every 60 s for nothing.

/** Selectable History time windows. */
export type HistRange = "all" | "24h" | "7d" | "1m" | "1y" | "custom";

const MINUTE_MS = 60_000;
const TEN_MIN_MS = 600_000;

/** Refetch quantum for a range: 60 s on 24H, 10 min on everything longer. */
export function trendQuantumMs(range: HistRange): number {
  return range === "24h" ? MINUTE_MS : TEN_MIN_MS;
}

/** Quantize an epoch-ms clock down to the quantum boundary. */
export function quantizeMs(nowMs: number, quantumMs: number): number {
  return Math.floor(nowMs / quantumMs) * quantumMs;
}
