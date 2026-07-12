export interface TrendPoint {
  t: number; soh: number | null; cell_spread_mv: number | null;
  temp_avg: number | null; temp_min: number | null; temp_max: number | null;
}
export interface TrendSeries { address: string; bucket_ms: number; first_ms: number | null; points: TrendPoint[] }
export interface ChargeSession {
  start_ms: number; end_ms: number; from_soc: number | null;
  duration_min: number; cv_tail_min: number; peak_temp_c: number | null;
}
export interface NoteRow { base_id: string; body: string; updated_at_ms: number }
