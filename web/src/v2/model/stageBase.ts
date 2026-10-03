// Which base IS "the chair" on v2's main stage — used by Command, Journey and the Fleet
// Health hero (WEB-12 / XC-1). Pure: the useStageBase hook feeds it the grouped fleet, the
// phone-synced seize threshold (GET /web/alert-config), the rail pin, this session's
// discharge memory and the previous answer.
//
// Ladder, first match wins — aligned with android resolveStage (model/Fleet.kt) and the
// share dock's resolve_active_group (server/app/routers/share.py) (XC-2):
//   1. SEIZE   a FRESH pack at/below the seize threshold stages its base; lowest SOC wins,
//              the daily driver breaks ties. Overrides the pin (android + v1 parity), so a
//              pack draining toward damage can never sit hidden off-stage.
//   2. PIN     a rail pin made less than PIN_HOLD_MS ago (android PIN_HOLD_MS): tapping a
//              spare shows it even while the chair is in use, and a forgotten pin can't
//              hide the chair for more than 30 minutes. A pin dated PIN_HOLD_MS or more
//              AHEAD of the clock (the clock stepped back since the tap) has expired too.
//   3. IN USE  a base with a fresh pack discharging; deepest draw wins (share.py), the
//              daily driver breaks exact ties.
//   4. HOLD    the base whose newest discharge (seen this session) is within
//              ACTIVE_HOLD_MS — stopped at a crossing, or a pack briefly out of BLE range.
//              Not gated on freshness (android: "idle OR briefly out of BLE range").
//   5. PARKED  the base this page staged last time, while ≥1 of its packs is fresh
//              (android "everything idle → leave the stage where it is"; share rung 3).
//   6. DEFAULT the daily driver if ≥1 of its packs is fresh; else the base with the newest
//              sample — among fresh packs, or, with none fresh (cold load, phone offline
//              mid-outing), across every pack, stale included. The phone polls its own
//              stage base fastest, so away from home that is the chair, not a spare left on
//              the charger. The daily driver only when no pack has a sample time at all.
//              Rung 5 then holds the choice, so "newest" can't flip-flop the way v1's
//              freshest-pack fallback does (XC-2's 17.6%).
// android's "a charging base may take over" rung is deliberately NOT ported, same as
// share.py: the spares live on chargers, so it would hand the stage straight to them.
import type { Base } from "../fleet";
import { DAILY_DRIVER_BASE, isDischarging } from "../fleet";

/** mirrors share.py ACTIVE_HOLD_MS and android DEFAULT_STAGE_HOLD_MIN. */
export const ACTIVE_HOLD_MS = 15 * 60_000;
/** mirrors android PIN_HOLD_MS. */
export const PIN_HOLD_MS = 30 * 60_000;
/** C6: the seize threshold when the phone has pushed none (same as v1 App.tsx). */
export const DEFAULT_SEIZE_SOC = 30;

export type StageReason = "seize" | "pin" | "in-use" | "hold" | "parked" | "default";
export interface StagePin { baseId: string; atMs: number }
export interface StageSelection { baseId: string; reason: StageReason }

export interface StageInputs {
  bases: Base[];
  /** SOC at/below which a fresh pack seizes the stage; null = seize off. */
  seizeThreshold: number | null;
  pin: StagePin | null;
  /** base id → ts_ms of the newest discharging sample seen this session (foldDischarge). */
  lastDischargeMs: ReadonlyMap<string, number>;
  /** The base this page staged on its previous evaluation, or null. */
  sticky: string | null;
  nowMs: number;
}

/** C6, exactly as v1: alerts off → no seize; otherwise the pushed value, 30 when absent. */
export function seizeThresholdFrom(cfg: { seize_soc: number | null; alerts_on: boolean }): number | null {
  return cfg.alerts_on === false ? null : (cfg.seize_soc ?? DEFAULT_SEIZE_SOC);
}

const isFresh = (b: Base): boolean => b.packs.some((p) => p.connected);
const dd = DAILY_DRIVER_BASE;
/** Deterministic tie-break: the daily driver first, then base id. */
const before = (a: string, b: string): boolean =>
  (a === dd) !== (b === dd) ? a === dd : a.localeCompare(b) < 0;

/** The base holding the newest sample (fresh packs only, or every pack); ties → before. */
function newestBase(bases: Base[], freshOnly: boolean): string | null {
  let newest: { id: string; ts: number } | null = null;
  for (const b of bases) {
    for (const p of b.packs) {
      const ts = p.item.ts_ms;
      if ((freshOnly && !p.connected) || !Number.isFinite(ts)) continue;
      if (!newest || ts > newest.ts || (ts === newest.ts && before(b.id, newest.id))) newest = { id: b.id, ts };
    }
  }
  return newest?.id ?? null;
}

export function selectStageBase(i: StageInputs): StageSelection | null {
  if (i.bases.length === 0) return null;
  const byId = new Map(i.bases.map((b) => [b.id, b] as const));

  // 1. SEIZE
  if (i.seizeThreshold != null) {
    let lead: { id: string; soc: number } | null = null;
    for (const b of i.bases) {
      for (const p of b.packs) {
        const soc = p.item.soc;
        if (!p.connected || soc == null || soc > i.seizeThreshold) continue;
        if (!lead || soc < lead.soc || (soc === lead.soc && before(b.id, lead.id))) lead = { id: b.id, soc };
      }
    }
    if (lead) return { baseId: lead.id, reason: "seize" };
  }

  // 2. PIN — expires both ways, so a future-dated pin can't hold the stage forever.
  if (i.pin && byId.has(i.pin.baseId) && Math.abs(i.nowMs - i.pin.atMs) < PIN_HOLD_MS) {
    return { baseId: i.pin.baseId, reason: "pin" };
  }

  // 3. IN USE
  let draw: { id: string; amps: number } | null = null;
  for (const b of i.bases) {
    for (const p of b.packs) {
      if (!p.connected || !isDischarging(p.item)) continue;
      const amps = p.item.current_a as number;
      if (!draw || amps < draw.amps || (amps === draw.amps && before(b.id, draw.id))) draw = { id: b.id, amps };
    }
  }
  if (draw) return { baseId: draw.id, reason: "in-use" };

  // 4. HOLD
  let held: { id: string; ts: number } | null = null;
  for (const [id, ts] of i.lastDischargeMs) {
    if (!byId.has(id) || i.nowMs - ts >= ACTIVE_HOLD_MS) continue;
    if (!held || ts > held.ts) held = { id, ts };
  }
  if (held) return { baseId: held.id, reason: "hold" };

  // 5. PARKED
  const sticky = i.sticky != null ? byId.get(i.sticky) : undefined;
  if (sticky && isFresh(sticky)) return { baseId: sticky.id, reason: "parked" };

  // 6. DEFAULT
  const ddBase = byId.get(dd);
  if (ddBase && isFresh(ddBase)) return { baseId: dd, reason: "default" };
  const newest = newestBase(i.bases, true) ?? newestBase(i.bases, false);
  if (newest) return { baseId: newest, reason: "default" };
  return { baseId: ddBase ? dd : i.bases[0].id, reason: "default" };
}

/** Fold this render's discharge observations into the session memory the HOLD rung reads.
 *  Only fresh packs count (a stale row's current is history, not news). Returns [prev]
 *  itself when nothing advanced, so a memo keyed on it stays put. */
export function foldDischarge(
  prev: ReadonlyMap<string, number>, bases: Base[],
): ReadonlyMap<string, number> {
  let next: Map<string, number> | null = null;
  for (const b of bases) {
    for (const p of b.packs) {
      if (!p.connected || !isDischarging(p.item)) continue;
      const seen = (next ?? prev).get(b.id);
      if (seen != null && seen >= p.item.ts_ms) continue;
      if (next == null) next = new Map(prev);
      next.set(b.id, p.item.ts_ms);
    }
  }
  return next ?? prev;
}

export const sameSelection = (a: StageSelection | null, b: StageSelection | null): boolean =>
  a?.baseId === b?.baseId && a?.reason === b?.reason;

/** localStorage codec half for the rail pin; anything malformed reads as "no pin". */
export function decodeStagePin(raw: string): StagePin | null {
  try {
    const o = JSON.parse(raw) as unknown;
    if (typeof o !== "object" || o === null) return null;
    const { baseId, atMs } = o as Record<string, unknown>;
    return typeof baseId === "string" && baseId !== "" && typeof atMs === "number" && Number.isFinite(atMs)
      ? { baseId, atMs } : null;
  } catch {
    return null;
  }
}
