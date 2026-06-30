export interface Sample {
  address: string; ts_ms: number; state?: string | null;
  soc?: number | null; current_a?: number | null; power_w?: number | null;
  voltage_v?: number | null; temp_c?: number | null; soh?: number | null;
  cycles?: number | null; regen?: boolean; link_event?: string | null;
}
export type FleetItem = Sample & { alias?: string | null; group_id?: string | null };
export interface DeviceRow {
  id: string; install_uuid: string; label?: string | null;
  last_seen_at?: string | null; revoked: boolean;
}
