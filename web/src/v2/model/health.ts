import type { FleetItem } from "../../types";
import { isCharging, isDischarging } from "../fleet";

export const READY_SOC = 90;
export const RECHARGE_SOC = 30;
export const DEGRADED_SOH = 80;

export interface HealthSummary {
  ready: number; needRecharge: number; degraded: number;
  capacityPct: number;
  /** Offline packs folded into each figure on their LAST-KNOWN reading. */
  readyStale: number; needRechargeStale: number; staleCounted: number;
}

/**
 * Every figure here counts offline packs on their LAST-KNOWN reading, because a
 * pack out of BLE range still holds the charge we last saw — dropping it made
 * the fleet read emptier than it is (worst case: away from the spares, the
 * tiles claimed 0 ready and 0% capacity). The *Stale counts let each tile say
 * how much of its number is last-known rather than live.
 */
export function healthSummary(items: FleetItem[], staleAddrs: Set<string>): HealthSummary {
  const stale = (i: FleetItem) => staleAddrs.has(i.address);
  const readyPacks = items.filter((i) => (i.soc ?? -1) >= READY_SOC);
  const lowPacks = items.filter((i) => (i.soc ?? Infinity) < RECHARGE_SOC);
  const degraded = items.filter((i) => i.soh != null && i.soh < DEGRADED_SOH).length;
  let rem = 0, full = 0, staleCounted = 0;
  for (const i of items) {
    if (i.remaining_ah != null && i.full_charge_ah != null && i.full_charge_ah > 0) {
      rem += i.remaining_ah; full += i.full_charge_ah;
      if (stale(i)) staleCounted++;
    }
  }
  return {
    ready: readyPacks.length, readyStale: readyPacks.filter(stale).length,
    needRecharge: lowPacks.length, needRechargeStale: lowPacks.filter(stale).length,
    degraded, capacityPct: full > 0 ? (rem / full) * 100 : 0, staleCounted,
  };
}

/** Attention-first: disconnected packs first, then ascending SOC (nulls last), stable. */
export function healthBoardOrder(items: FleetItem[], staleAddrs: Set<string>): FleetItem[] {
  return items
    .map((i, idx) => ({ i, idx, off: staleAddrs.has(i.address) }))
    .sort((a, b) => {
      if (a.off !== b.off) return a.off ? -1 : 1;
      const sa = a.i.soc ?? Infinity, sb = b.i.soc ?? Infinity;
      return sa !== sb ? sa - sb : a.idx - b.idx;
    })
    .map((x) => x.i);
}

export type PackStatus = "in-use" | "charging" | "low" | "idle" | "offline";
export function packStatus(item: FleetItem, connected: boolean): PackStatus {
  if (!connected) return "offline";
  if (isDischarging(item)) return "in-use";
  if (isCharging(item)) return "charging";
  if ((item.soc ?? Infinity) < RECHARGE_SOC) return "low";
  return "idle";
}
