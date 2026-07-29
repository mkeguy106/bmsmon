export interface TrackPoint {
  t: number; lat: number; lon: number;
  power_w: number | null; current_a: number | null; soc: number | null;
  /** Mean GPS accuracy radius (metres) for the bucket; null when unreported. */
  acc: number | null;
  /** Set by kalmanTrack: the segment from the PREVIOUS point to this one is inferred,
   *  not measured (a GPS hole longer than COAST_MAX_MS). Never set on the first point. */
  inferred?: boolean;
}
export interface Track { address: string; points: TrackPoint[] }
