import type { FleetItem } from "../../types";
import { deltaMv } from "../fleet";
import {
  tempZone, zoneCopy, thresholdsFromConfig, envelopeFromConfig, type TempConfig,
} from "../../temp";

export type AlertSeverity = "critical" | "warning";
export interface V2Alert {
  id: string; address: string; severity: AlertSeverity;
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
        out.push({ id: `temp:${i.address}`, address: i.address, kind: "temp",
          severity: z.rank >= 3 ? "critical" : "warning", title: c.title, msg: c.msg, tsMs: i.ts_ms });
      }
    }
    // cell imbalance
    const dvRaw = deltaMv(i);
    // Round before thresholding: cell voltages are floats, so e.g. (3.36-3.30)*1000
    // lands on 60.00000000000006 in IEEE-754, not exactly 60 — round first so the
    // mV comparison matches the (already-rounded) mV value shown in the message.
    const dv = dvRaw != null ? Math.round(dvRaw) : null;
    if (dv != null && dv > 40) {
      out.push({ id: `cell:${i.address}`, address: i.address, kind: "cell",
        severity: dv > 60 ? "critical" : "warning",
        title: "Cell imbalance", msg: `Δ ${dv} mV across cells.`, tsMs: i.ts_ms });
    }
  }
  const rank = (s: AlertSeverity) => (s === "critical" ? 0 : 1);
  return out.sort((a, b) => rank(a.severity) - rank(b.severity) || b.tsMs - a.tsMs);
}
