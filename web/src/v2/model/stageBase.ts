// Which base IS "the chair" on v2's main stage — used by Command, Journey and the Fleet
// Health hero (WEB-12 / XC-1). Pure: the useStageBase hook feeds it the grouped fleet, the
// phone-synced seize threshold (GET /web/alert-config), the rail pin, this session's
// discharge memory and the previous answer.
//
// Ladder, first match wins — aligned with android resolveStage (model/Fleet.kt) and the
// share dock's resolve_active_group (server/app/routers/share.py) (XC-2):
//   1. SEIZE   a FRESH pack at/below the seize threshold stages its base; lowest SOC wins,
//              the daily driver breaks ties. Overrides the pin (android + v1 parity), so a
//              pack draining toward damage can never sit hidden off-stage. Candidates are
//              limited to the bases in use (every base with a discharging fresh pack, else
//              the one base that discharged within ACTIVE_HOLD_MS) whenever any is in use,
//              so an idle spare on a charger never displaces the chair; with none in use any
//              fresh pack may seize. A pack that is charging (state Charging or current above
//              +0.05 A) never seizes unless it is regenerating: its base discharged within
//              REGEN_WINDOW_MS, a pack on its base is discharging in this same render (kept
//              so the answer never depends on the session memory having been folded yet), or
//              the row's own regen flag is set.
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
/** mirrors android's regen window: a charging reading this soon after a discharge is braking. */
export const REGEN_WINDOW_MS = 30_000;
/** Current above which a pack counts as charging (mirrors android). */
const CHARGING_A = 0.05;
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

/** C6, exactly as v1: alerts off → no seize; otherwise the pushed value, 30 when absent.
 *  A config not yet known (null) seizes nothing: a seize made on a guessed threshold could
 *  stay PARKED on that base after the real config arrives with alerts off. */
export function seizeThresholdFrom(
  cfg: { seize_soc: number | null; alerts_on: boolean } | null,
): number | null {
  if (cfg == null) return null;
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

/** The SEIZE rung: the base of the lowest-SOC candidate pack, or null. Candidates are fresh
 *  packs at/below the threshold, on a base in use whenever any base is, and not charging
 *  unless regenerating (see the header): the base discharged within REGEN_WINDOW_MS, a pack
 *  on it discharges in this render (so this never relies on lastDischargeMs having been
 *  folded), or the row's regen flag. Ties go to the daily driver, then `before`. */
/** Equal-SOC seize tie, as android `seizeCandidate`: a pack on the daily-driver base first, then
 *  the lowest pack address (ordinal string order, like Kotlin's String.compareTo). */
const seizeTieBefore = (baseA: string, addrA: string, baseB: string, addrB: string): boolean =>
  (baseA === dd) !== (baseB === dd) ? baseA === dd : addrA < addrB;

function seizeLead(i: StageInputs): string | null {
  if (i.seizeThreshold == null) return null;
  const inUse = new Set<string>();
  for (const b of i.bases) {
    if (b.packs.some((p) => p.connected && isDischarging(p.item))) inUse.add(b.id);
  }
  if (inUse.size === 0) {
    let held: { id: string; ts: number } | null = null;
    for (const [id, ts] of i.lastDischargeMs) {
      if (!i.bases.some((b) => b.id === id) || i.nowMs - ts >= ACTIVE_HOLD_MS) continue;
      if (!held || ts > held.ts) held = { id, ts };
    }
    if (held) inUse.add(held.id);
  }
  let lead: { id: string; soc: number; addr: string } | null = null;
  for (const b of i.bases) {
    if (inUse.size > 0 && !inUse.has(b.id)) continue;
    const seen = i.lastDischargeMs.get(b.id);
    const driving = b.packs.some((p) => p.connected && isDischarging(p.item)) ||
      (seen != null && i.nowMs - seen < REGEN_WINDOW_MS);
    for (const p of b.packs) {
      const soc = p.item.soc;
      if (!p.connected || soc == null || soc > i.seizeThreshold) continue;
      const charging = p.item.state === "Charging" || (p.item.current_a ?? 0) > CHARGING_A;
      if (charging && !driving && !p.item.regen) continue;
      const addr = p.item.address;
      if (!lead || soc < lead.soc || (soc === lead.soc && seizeTieBefore(b.id, addr, lead.id, lead.addr))) {
        lead = { id: b.id, soc, addr };
      }
    }
  }
  return lead?.id ?? null;
}

export function selectStageBase(i: StageInputs): StageSelection | null {
  if (i.bases.length === 0) return null;
  const byId = new Map(i.bases.map((b) => [b.id, b] as const));

  // 1. SEIZE
  const seized = seizeLead(i);
  if (seized) return { baseId: seized, reason: "seize" };

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
