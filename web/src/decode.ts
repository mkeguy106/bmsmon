// WEB-4: hand-rolled runtime decoders for the two untrusted entry points (WS
// frames, REST responses). No schema library — the app stays zero-framework.
// Decoders coerce nothing: they validate the identity fields, whitelist known
// keys (unknown keys — including the WS "type" tag — are dropped), and
// tolerate null/undefined optionals. Garbage returns null (with a
// console.warn) so callers can drop a frame or throw without crashing.
import type { FleetItem, Sample } from "./types";
import type { TempConfig } from "./temp";

// Explicit whitelists mirroring types.ts / temp.ts — when a field the UI reads
// is added there, add it here too or the decoder will strip it.
const SAMPLE_KEYS = [
  "address", "ts_ms", "state", "soc", "current_a", "power_w", "voltage_v",
  "temp_c", "soh", "cycles", "regen", "link_event",
  "lat", "lon", "gps_accuracy_m", "eta_full_min",
] as const;
const FLEET_KEYS = [...SAMPLE_KEYS, "alias", "group_id"] as const;
const TEMP_CONFIG_KEYS = [
  "device_id", "profile_id", "cold_caution_c", "hot_caution_c",
  "cold_crit_c", "hot_crit_c", "unit", "updated_at_ms", "received_at",
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

const validTempConfig = (x: unknown): x is Record<string, unknown> =>
  isObj(x) &&
  typeof x.device_id === "string" && typeof x.profile_id === "string" &&
  Number.isFinite(x.cold_caution_c) && Number.isFinite(x.hot_caution_c) &&
  Number.isFinite(x.cold_crit_c) && Number.isFinite(x.hot_crit_c) &&
  typeof x.unit === "string" && Number.isFinite(x.updated_at_ms);

/**
 * Strict: any malformed config invalidates the whole response — the caller
 * throws and the UI keeps its last-known config (better than silently showing
 * Redodo defaults because a bad config row got dropped).
 */
export function decodeTempConfigs(x: unknown): TempConfig[] | null {
  if (!Array.isArray(x) || !x.every(validTempConfig)) return warn("temp-config", x);
  return x.map((c) => pick<TempConfig>(c, TEMP_CONFIG_KEYS));
}
