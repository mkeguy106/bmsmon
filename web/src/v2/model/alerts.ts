import type { FleetItem } from "../../types";
import { deltaMv } from "../fleet";
import {
  tempZone, zoneCopy, thresholdsFromConfig, envelopeFromConfig, type TempConfig,
} from "../../temp";

export type AlertSeverity = "critical" | "warning";
export interface V2Alert {
  /** One condition on one pack (`cap:<addr>`, `temp:<addr>:<side>`, `cell:<addr>`). Stable
   *  while the condition lasts — it does NOT change as the condition worsens; [rank] does. */
  id: string; address: string; severity: AlertSeverity;
  /** How bad, within this id: capacity rung crossed (30%→1 … 5%→6), temperature zone rank
   *  (1–4), cell imbalance 1 warning / 2 critical. An ack silences ranks up to the one acked. */
  rank: number;
  title: string; msg: string; tsMs: number; kind: "capacity" | "temp" | "cell";
}

export const CAPACITY_LADDER = [30, 25, 20, 15, 10, 5];
export const CRITICAL_SOC = 15;

/** Highest ladder rung the SOC is at/below, or null if above the top rung. */
function crossedRung(soc: number): number | null {
  let hit: number | null = null;
  for (const r of CAPACITY_LADDER) if (soc <= r) hit = hit == null ? r : Math.min(hit, r);
  return hit;
}

export function deriveAlerts(
  items: FleetItem[], staleAddrs: Set<string>, tempCfg: TempConfig | null,
): V2Alert[] {
  const out: V2Alert[] = [];
  const thr = thresholdsFromConfig(tempCfg), env = envelopeFromConfig(tempCfg);
  for (const i of items) {
    if (staleAddrs.has(i.address)) continue;
    // capacity
    if (i.soc != null) {
      const rung = crossedRung(i.soc);
      if (rung != null) {
        const critical = i.soc <= CRITICAL_SOC;
        out.push({ id: `cap:${i.address}`, address: i.address, kind: "capacity",
          rank: CAPACITY_LADDER.indexOf(rung) + 1,
          severity: critical ? "critical" : "warning",
          title: critical ? "Critically low" : "Low battery",
          msg: `${Math.round(i.soc)}% — recharge soon.`, tsMs: i.ts_ms });
      }
    }
    // temperature (reuse temp.ts)
    if (i.temp_c != null) {
      const z = tempZone(i.temp_c, thr, env);
      if (z.rank >= 1) {
        const c = zoneCopy(z.key, thr, env);
        // Per side: a hot ack must never silence a later cold alert on the same pack.
        out.push({ id: `temp:${i.address}:${z.side}`, address: i.address, kind: "temp", rank: z.rank,
          severity: z.rank >= 3 ? "critical" : "warning", title: c.title, msg: c.msg, tsMs: i.ts_ms });
      }
    }
    // cell imbalance
    const dv = deltaMv(i);
    // Epsilon-compare the raw mV so IEEE-754 dust doesn't flip a boundary: e.g.
    // (3.36-3.30)*1000 = 60.00000000000006, which must still read as a 60 mV warning,
    // not a critical. EPS is far below telemetry's integer-mV granularity, so a true
    // 40/60 boundary is exact while the float artifact is absorbed. Display rounds.
    const EPS = 1e-6;
    if (dv != null && dv > 40 + EPS) {
      const critical = dv > 60 + EPS;
      out.push({ id: `cell:${i.address}`, address: i.address, kind: "cell", rank: critical ? 2 : 1,
        severity: critical ? "critical" : "warning",
        title: "Cell imbalance", msg: `Δ ${Math.round(dv)} mV across cells.`, tsMs: i.ts_ms });
    }
  }
  const rank = (s: AlertSeverity) => (s === "critical" ? 0 : 1);
  return out.sort((a, b) => rank(a.severity) - rank(b.severity) || b.tsMs - a.tsMs);
}

// ── Acknowledgement lifecycle (WEB-17) ─────────────────────────────────────
// v1's nextAckedKey principle, generalised: an ack covers the condition AS SEEN. It holds
// while the condition stays at or below the acked rank (a pack charging back up through
// the rungs does not re-nag), re-arms the moment it gets worse (android: "acknowledged
// thresholds silence until SOC drops to the next level"), and is forgotten once the
// condition clears on a pack that is still reporting — so the next episode alerts afresh.

export interface AckEntry { rank: number; address: string }
export type AckMap = ReadonlyMap<string, AckEntry>;

export function isAcked(acked: AckMap, a: V2Alert): boolean {
  const e = acked.get(a.id);
  return e != null && a.rank <= e.rank;
}

export function ackAlert(acked: AckMap, a: V2Alert): AckMap {
  const next = new Map(acked);
  next.set(a.id, { rank: a.rank, address: a.address });
  return next;
}

/** Forget acks whose condition has cleared. A pack that has merely gone STALE keeps its
 *  acks: deriveAlerts drops stale packs, so a BLE flap would otherwise re-nag on every
 *  reconnect (the web twin of android BLE-24). Identity-stable when nothing is dropped. */
export function pruneAcks(acked: AckMap, alerts: V2Alert[], staleAddrs: Set<string>): AckMap {
  if (acked.size === 0) return acked;
  const live = new Set(alerts.map((a) => a.id));
  let next: Map<string, AckEntry> | null = null;
  for (const [id, e] of acked) {
    if (live.has(id) || staleAddrs.has(e.address)) continue;
    if (next == null) next = new Map(acked);
    next.delete(id);
  }
  return next ?? acked;
}

export function unackedCount(alerts: V2Alert[], acked: AckMap): number {
  return alerts.filter((a) => !isAcked(acked, a)).length;
}
