export interface TrackPoint {
  t: number; lat: number; lon: number;
  power_w: number | null; current_a: number | null; soc: number | null;
  /** Mean GPS accuracy radius (metres) for the bucket; null when unreported. */
  acc: number | null;
  /** Set by kalmanTrack: the segment from the PREVIOUS point to this one is inferred,
   *  not measured (a GPS hole longer than COAST_MAX_MS). Never set on the first point. */
  inferred?: boolean;
  /** Set by mergeBaseTracks: how many of the base's packs reported in this bucket. A bucket
   *  short of a pack holds only part of the base's power (efficiency.ts scales it). */
  packs?: number;
}
export interface Track { address: string; points: TrackPoint[] }
