// WEB-4: hand-rolled runtime decoders for the two untrusted entry points (WS
// frames, REST responses). No schema library — the app stays zero-framework.
// Decoders coerce nothing: they validate the identity fields, whitelist known
// keys (unknown keys — including the WS "type" tag — are dropped), and
// tolerate null/undefined optionals. Garbage returns null (with a
// console.warn) so callers can drop a frame or throw without crashing.
import type { FleetItem, Sample } from "./types";
import type { TempConfig } from "./temp";
import type { RangeConfigRow } from "./range";

// Explicit whitelists mirroring types.ts / temp.ts — when a field the UI reads
// is added there, add it here too or the decoder will strip it.
const SAMPLE_KEYS = [
  "address", "ts_ms", "state", "soc", "current_a", "power_w", "voltage_v",
  "temp_c", "soh", "cycles", "full_charge_ah", "remaining_ah", "regen", "link_event",
  "lat", "lon", "gps_accuracy_m", "eta_full_min",
] as const;
const FLEET_KEYS = [...SAMPLE_KEYS, "alias", "group_id"] as const;
// WEB-6: envelope fields are optional (older rows/servers omit them) and may
// be null — validated as optional numerics, never required.
const TEMP_ENVELOPE_KEYS = [
  "cutoff_cold_c", "cutoff_hot_c", "charge_lock_cold_c", "charge_lock_hot_c",
  "charge_resume_cold_c",
] as const;
const TEMP_CONFIG_KEYS = [
  "device_id", "profile_id", "cold_caution_c", "hot_caution_c",
  "cold_crit_c", "hot_crit_c", "unit", "updated_at_ms", "received_at",
  ...TEMP_ENVELOPE_KEYS,
] as const;

const isObj = (x: unknown): x is Record<string, unknown> =>
  typeof x === "object" && x !== null;

const pick = <T>(x: Record<string, unknown>, keys: readonly string[]): T => {
  const out: Record<string, unknown> = {};
  for (const k of keys) if (k in x) out[k] = x[k];
  return out as unknown as T;
};

const warn = (what: string, x: unknown): null => {
  console.warn(`bmsmon: dropping malformed ${what}`, x);
  return null;
};

const hasIdentity = (x: unknown): x is Record<string, unknown> =>
  isObj(x) && typeof x.address === "string" && Number.isFinite(x.ts_ms);

/** A sample is only usable with a string address and a finite ts_ms. */
export function decodeSample(x: unknown): Sample | null {
  if (!hasIdentity(x)) return warn("sample", x);
  return pick<Sample>(x, SAMPLE_KEYS);
}

/** Snapshot items additionally carry alias/group_id meta. */
export function decodeFleetItem(x: unknown): FleetItem | null {
  if (!hasIdentity(x)) return warn("fleet item", x);
  return pick<FleetItem>(x, FLEET_KEYS);
}

/** null when the snapshot itself is malformed; individually bad items are dropped. */
export function decodeSnapshot(x: unknown): FleetItem[] | null {
  if (!Array.isArray(x)) return warn("snapshot", x);
  const out: FleetItem[] = [];
  for (const item of x) {
    const d = decodeFleetItem(item);
    if (d) out.push(d);
  }
  return out;
}

const optionalNum = (v: unknown): boolean => v == null || Number.isFinite(v);

const validTempConfig = (x: unknown): x is Record<string, unknown> =>
  isObj(x) &&
  typeof x.device_id === "string" && typeof x.profile_id === "string" &&
  Number.isFinite(x.cold_caution_c) && Number.isFinite(x.hot_caution_c) &&
  Number.isFinite(x.cold_crit_c) && Number.isFinite(x.hot_crit_c) &&
  typeof x.unit === "string" && Number.isFinite(x.updated_at_ms) &&
  TEMP_ENVELOPE_KEYS.every((k) => optionalNum(x[k]));

/**
 * Strict: any malformed config invalidates the whole response — the caller
 * throws and the UI keeps its last-known config (better than silently showing
 * Redodo defaults because a bad config row got dropped).
 */
export function decodeTempConfigs(x: unknown): TempConfig[] | null {
  if (!Array.isArray(x) || !x.every(validTempConfig)) return warn("temp-config", x);
  return x.map((c) => pick<TempConfig>(c, TEMP_CONFIG_KEYS));
}

const RANGE_CONFIG_KEYS = [
  "device_id", "address", "wh_per_day_lo", "wh_per_day_hi", "active_w_lo", "active_w_hi",
  "wh_per_mile_lo", "wh_per_mile_hi", "learned_days", "updated_at_ms",
] as const;

const validRangeConfig = (x: unknown): x is Record<string, unknown> =>
  isObj(x) && typeof x.device_id === "string" && typeof x.address === "string" &&
  Number.isFinite(x.wh_per_day_lo) && Number.isFinite(x.wh_per_day_hi) &&
  Number.isFinite(x.active_w_lo) && Number.isFinite(x.active_w_hi) &&
  Number.isFinite(x.wh_per_mile_lo) && Number.isFinite(x.wh_per_mile_hi) &&
  Number.isFinite(x.updated_at_ms) &&
  (x.learned_days == null || Number.isFinite(x.learned_days));

/** Strict like decodeTempConfigs: any malformed row invalidates the response. */
export function decodeRangeConfigs(x: unknown): RangeConfigRow[] | null {
  if (!Array.isArray(x) || !x.every(validRangeConfig)) return warn("range-config", x);
  return x.map((c) => {
    const row = pick<RangeConfigRow>(c, RANGE_CONFIG_KEYS);
    return { ...row, learned_days: row.learned_days ?? 0 };
  });
}
