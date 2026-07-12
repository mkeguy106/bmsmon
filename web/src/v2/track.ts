export interface TrackPoint {
  t: number; lat: number; lon: number;
  power_w: number | null; current_a: number | null; soc: number | null;
}
export interface Track { address: string; points: TrackPoint[] }
