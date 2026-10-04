// The Command aside's recharge plan: every pack charging toward full, anchored to the sample
// that carried its ETA (WEB-18). It used to compute "ready by" as now + ETA, so a spare that
// went out of range while charging read as charging live with a ready time that slid
// forward forever. A pack that is not live stays listed, marked last known, because "are my
// spares ready?" is exactly the question asked from away from home.
import type { Base } from "../fleet";

export interface RechargeRow {
  address: string;
  label: string;
  soc: number | null;
  live: boolean;
  /** When the ETA was computed: the sample's own timestamp. */
  tsMs: number;
  etaMin: number;
  /** tsMs + ETA. Never re-anchored to the clock. */
  readyAtMs: number;
}

/** Packs with a finite, positive ETA below 99% SOC; live rows first, then by ready time. */
export function rechargePlan(bases: readonly Base[]): RechargeRow[] {
  const rows: RechargeRow[] = [];
  for (const b of bases) {
    for (const p of b.packs) {
      const eta = p.item.eta_full_min;
      if (eta == null || !Number.isFinite(eta) || eta <= 0) continue;
      if ((p.item.soc ?? 100) >= 99) continue;
      rows.push({
        address: p.item.address, label: `Base ${b.id} · ${p.letter}`, soc: p.item.soc ?? null,
        live: p.connected, tsMs: p.item.ts_ms, etaMin: eta, readyAtMs: p.item.ts_ms + eta * 60_000,
      });
    }
  }
  return rows.sort((a, b) => (a.live !== b.live ? (a.live ? -1 : 1) : a.readyAtMs - b.readyAtMs));
}

/** "charging": live. "due": last known, its ready time still ahead. "past-due": last known
 *  and the ready time has passed; it has probably finished, but nobody has seen it. */
export type RechargePhase = "charging" | "due" | "past-due";

export function rechargePhase(r: RechargeRow, nowMs: number): RechargePhase {
  if (r.live) return "charging";
  return nowMs < r.readyAtMs ? "due" : "past-due";
}

/** Minutes left until the anchored ready time, never below zero. */
export function minutesLeft(r: RechargeRow, nowMs: number): number {
  return Math.max(0, (r.readyAtMs - nowMs) / 60_000);
}
