// The base view-model for v2 (review T2.1): what the Command stage, the range card and the
// Journey efficiency card show about ONE base, decided in one pure, tested place. Before
// this each component derived it inline, and they disagreed (WEB-14, 15, 19, 31).
//
// Rules:
// - Every pack is in the view. A pack that is not live keeps its LAST-KNOWN telemetry,
//   rendered muted and timestamped: never blanked while a reading exists, never posing as live.
// - Instantaneous figures (flow watts, charging, time to full, temperature) come from LIVE
//   packs only. A stale current or temperature is history, not news.
// - The weaker pack bounds the base. The pair is in series: both packs carry the same
//   current, so the first to empty ends the trip. The bound runs over live AND last-known
//   values. A pack that dropped off BLE is still wired in and still discharging, so its last
//   reading is an upper bound on its charge; leaving it out made range and GO/NO-GO
//   optimistic. When a last-known value is in the bound, the view names it.
// - A pack with no usable capacity reading could be the weaker one, so the base has no
//   bound at all: energy is unknown and range reads no-data. A bound from the packs that do
//   report would be an overestimate with nothing to flag it.
import type { Base } from "../fleet";
import { baseLastSeenMs, isCharging } from "../fleet";
import type { FleetItem } from "../../types";
import {
  NOMINAL_PACK_V, SEED_RANGE_PARAMS, estimatePackRange, minRange,
  type PackRange, type RangeParams,
} from "../../range";
import {
  envelopeFromConfig, tempZone, thresholdsFromConfig, zoneCopy, type TempConfig, type Zone,
} from "../../temp";

export interface PackView {
  item: FleetItem;
  letter: string;
  /** Fresh telemetry with the link up. False = last known: render muted, with "last seen". */
  live: boolean;
}

/** A last-known pack inside a base-level bound, named in the UI. */
export interface LastKnownRef { letter: string; tsMs: number }

export type RangeState =
  /** A live pack is charging: the recharge ETA owns the slot. */
  | { kind: "charging"; etaFullMin: number | null }
  /** No pack is live. */
  | { kind: "offline"; lastSeenMs: number | null }
  /** Live, but some pack (live or last known) reports no usable remaining capacity. */
  | { kind: "no-data" }
  | { kind: "estimate"; range: PackRange; lastKnown: LastKnownRef | null };

export interface ThermalView {
  zone: Zone;
  /** Same split as the Alerts view: zone rank ≥ 3 is critical. */
  severity: "critical" | "warning";
  letter: string;
  tempC: number;
  title: string;
  msg: string;
}

export interface BaseView {
  packs: PackView[];
  livePacks: PackView[];
  /** Σ |power| over live packs; null when none is live. */
  flowW: number | null;
  /** A live pack is charging. A regen burst while driving is not charging. */
  charging: boolean;
  /** The longest live time-to-full, in minutes; null when no live pack reports one. */
  etaFullMin: number | null;
  range: RangeState;
  /** Usable base energy in Wh: pack count × the weaker pack's remaining Ah × nominal V, over
   *  live and last-known readings (WEB-15: the sum overstated a series pair). Null when any
   *  pack reports no usable remaining capacity, since that pack could be the weaker one. */
  usableWh: number | null;
  /** The last-known pack inside usableWh, if any. */
  usableLastKnown: LastKnownRef | null;
  /** Range params for every pack, seed fallback (missing or invalid synced params), so the
   *  band matches usableWh's basis. */
  packParams: RangeParams[];
  /** The worst temperature zone (rank ≥ 1) over live packs, from the phone-synced
   *  thresholds and envelope (WEB-31). */
  thermal: ThermalView | null;
}

export interface BaseViewInputs {
  rangeParams: ReadonlyMap<string, RangeParams>;
  tempConfig: TempConfig | null;
}

/** Charging as far as range and ETA are concerned: current flowing in, but not a regen burst
 *  while driving. The phone flags those, and a 20 s regen pulse must not flip the range
 *  card to "Charging". */
export const isChargingNow = (i: FleetItem): boolean => isCharging(i) && i.regen !== true;

/** A usable remaining capacity: finite and ≥ 0. Zero is real (an empty pack ends the trip
 *  now). A negative or non-finite value is a garbage reading and counts as none. */
function remainingAh(i: FleetItem): number | null {
  const ah = i.remaining_ah;
  return ah != null && Number.isFinite(ah) && ah >= 0 ? ah : null;
}

/** Synced params only when every band edge is finite and > 0, else the seed. range.ts gives
 *  no estimate for a bad band, and a pack missing from the min could be the weaker one. */
function validParams(p: RangeParams | undefined): RangeParams {
  if (p == null) return SEED_RANGE_PARAMS;
  const ok = [p.whPerDay, p.activeW, p.whPerMile].every((b) =>
    b != null && Number.isFinite(b.lo) && Number.isFinite(b.hi) && b.lo > 0 && b.hi > 0);
  return ok ? p : SEED_RANGE_PARAMS;
}

const ZERO_RANGE: PackRange = {
  milesLo: 0, milesHi: 0, activeHLo: 0, activeHHi: 0, wallHLo: 0, wallHHi: 0,
};

const ref = (p: PackView): LastKnownRef => ({ letter: p.letter, tsMs: p.item.ts_ms });

export function baseView(base: Base, inputs: BaseViewInputs): BaseView {
  const params = (i: FleetItem): RangeParams => validParams(inputs.rangeParams.get(i.address));
  const packs: PackView[] = base.packs.map((p) => ({ item: p.item, letter: p.letter, live: p.connected }));
  const livePacks = packs.filter((p) => p.live);

  const flowW = livePacks.length > 0
    ? livePacks.reduce((s, p) => s + Math.abs(p.item.power_w ?? 0), 0) : null;
  const charging = livePacks.some((p) => isChargingNow(p.item));
  const etaFullMin = livePacks.reduce<number | null>((mx, p) => {
    const e = p.item.eta_full_min;
    return e != null && Number.isFinite(e) && e > 0 ? Math.max(mx ?? 0, e) : mx;
  }, null);

  // The weaker pack over EVERY pack, or no bound at all when any pack has no usable capacity.
  const withAh = packs.map((p) => ({ p, ah: remainingAh(p.item) }));
  const complete = withAh.length > 0 && withAh.every((x): x is { p: PackView; ah: number } => x.ah != null)
    ? withAh : null;
  const stale = packs.find((p) => !p.live);
  const lastKnown = complete && stale ? ref(stale) : null;

  const usableWh = complete ? packs.length * Math.min(...complete.map((x) => x.ah)) * NOMINAL_PACK_V : null;

  // The range bound: every pack through the shared range formula (range.ts). With a usable
  // capacity and validated params it always yields an estimate; a null would still read no-data.
  const estimates = complete
    ? complete.map((x) => x.ah === 0 ? ZERO_RANGE : estimatePackRange(false, x.ah, params(x.p.item)))
    : null;
  const bound = estimates && estimates.every((r): r is PackRange => r != null) ? minRange(estimates) : null;

  let range: RangeState;
  if (livePacks.length === 0) range = { kind: "offline", lastSeenMs: baseLastSeenMs(base) };
  else if (charging) range = { kind: "charging", etaFullMin };
  else if (bound == null) range = { kind: "no-data" };
  else range = { kind: "estimate", range: bound, lastKnown };

  const thr = thresholdsFromConfig(inputs.tempConfig);
  const env = envelopeFromConfig(inputs.tempConfig);
  let thermal: ThermalView | null = null;
  for (const p of livePacks) {
    const t = p.item.temp_c;
    if (t == null || !Number.isFinite(t)) continue;
    const zone = tempZone(t, thr, env);
    if (zone.rank < 1 || (thermal && zone.rank <= thermal.zone.rank)) continue;
    const copy = zoneCopy(zone.key, thr, env);
    thermal = {
      zone, severity: zone.rank >= 3 ? "critical" : "warning",
      letter: p.letter, tempC: t, title: copy.title, msg: copy.msg,
    };
  }

  return {
    packs, livePacks, flowW, charging, etaFullMin, range, usableWh, usableLastKnown: lastKnown,
    packParams: packs.map((p) => params(p.item)), thermal,
  };
}
