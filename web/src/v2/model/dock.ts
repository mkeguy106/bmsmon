// Pure model for the mobile Journey dock's two lines: pair capacity + single-direction flow.
// Design: docs/superpowers/specs/2026-07-13-journey-mobile-design.md
import type { BasePack } from "../fleet";
import { DISCHARGE_EPS } from "./journey";

/** Flow full scale: 2 × the 300 W per-pack ring calibration (POWER_RING_FULL_W, android). */
export const PAIR_FLOW_FULL_W = 600;

export type FlowKind = "out" | "regen" | "chg" | "idle";
export interface DockFlow { kind: FlowKind; watts: number; frac: number }
export interface DockCap { pct: number | null; detail: string; band: "ok" | "warn" | "crit" }

/** Pair capacity: the weaker reachable pack ends the trip; per-pack detail keeps A/B visible. */
export function dockCapacity(packs: BasePack[]): DockCap {
  const reachable = packs.filter((p) => p.connected && p.item.soc != null);
  const pct = reachable.length ? Math.round(Math.min(...reachable.map((p) => p.item.soc!))) : null;
  const detail = packs.length > 1
    ? packs.map((p) => `${p.letter}${p.item.soc != null ? Math.round(p.item.soc) : "—"}`).join("·")
    : "";
  const band = pct == null || pct > 30 ? "ok" : pct > 15 ? "warn" : "crit";
  return { pct, detail, band };
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
