// Pure model for the mobile Journey dock's two lines: pair capacity + single-direction flow.
// Design: docs/superpowers/specs/2026-07-13-journey-mobile-design.md
import type { BasePack } from "../fleet";
import { DISCHARGE_EPS } from "./journey";

/** Flow full scale: 2 × the 300 W per-pack ring calibration (POWER_RING_FULL_W, android). */
export const PAIR_FLOW_FULL_W = 600;

export type FlowKind = "out" | "regen" | "chg" | "idle";
export interface DockFlow { kind: FlowKind; watts: number; frac: number }
export interface DockCap {
  pct: number | null; detail: string; band: "ok" | "warn" | "crit";
  /** The bound is a pack's last-known reading (it is not live), so the true value may be lower. */
  lastKnown: boolean;
}

/** Pair capacity: the weaker pack ends the trip; per-pack detail keeps A/B visible. The bound
 *  runs over live AND last-known SOC, the same rule as the base view-model (WEB-14): a pack
 *  that dropped off BLE is still in the circuit, so leaving it out read optimistic. At a tie
 *  the live pack is the bound, so the flag is only raised when it matters. */
export function dockCapacity(packs: BasePack[]): DockCap {
  let weakest: BasePack | null = null;
  for (const p of packs) {
    const soc = p.item.soc;
    if (soc == null || !Number.isFinite(soc)) continue;
    const w = weakest?.item.soc;
    if (weakest == null || w == null || soc < w || (soc === w && p.connected && !weakest.connected)) weakest = p;
  }
  const rawMin = weakest?.item.soc ?? null;
  const pct = rawMin != null ? Math.round(rawMin) : null;
  const detail = packs.length > 1
    ? packs.map((p) => `${p.letter}${p.item.soc != null ? Math.round(p.item.soc) : "—"}`).join("·")
    : "";
  // Band from the RAW min soc (pre-rounding) so CAP and socColor agree at boundaries.
  const band = rawMin == null || rawMin > 30 ? "ok" : rawMin > 15 ? "warn" : "crit";
  return { pct, detail, band, lastKnown: weakest != null && !weakest.connected };
}

/** One flow line: direction from the summed pair current, magnitude vs the 600 W full scale. */
export function dockFlow(packs: BasePack[]): DockFlow {
  const live = packs.filter((p) => p.connected);
  const current = live.reduce((s, p) => s + (p.item.current_a ?? 0), 0);
  const watts = Math.round(Math.abs(live.reduce((s, p) => s + (p.item.power_w ?? 0), 0)));
  const frac = Math.min(1, watts / PAIR_FLOW_FULL_W);
  if (current < -DISCHARGE_EPS) return { kind: "out", watts, frac };
  if (current > DISCHARGE_EPS) {
    return { kind: live.some((p) => p.item.regen === true) ? "regen" : "chg", watts, frac };
  }
  return { kind: "idle", watts: 0, frac: 0 };
}
