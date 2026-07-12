import type { FleetItem } from "../../types";
import { isCharging, isDischarging } from "../fleet";

export const READY_SOC = 90;
export const RECHARGE_SOC = 30;
export const DEGRADED_SOH = 80;

export interface HealthSummary { ready: number; needRecharge: number; degraded: number; capacityPct: number }

export function healthSummary(items: FleetItem[], staleAddrs: Set<string>): HealthSummary {
  const live = items.filter((i) => !staleAddrs.has(i.address));
  const ready = live.filter((i) => (i.soc ?? -1) >= READY_SOC).length;
  const needRecharge = live.filter((i) => (i.soc ?? Infinity) < RECHARGE_SOC).length;
  const degraded = items.filter((i) => i.soh != null && i.soh < DEGRADED_SOH).length;
  let rem = 0, full = 0;
  for (const i of live) {
    if (i.remaining_ah != null && i.full_charge_ah != null && i.full_charge_ah > 0) {
      rem += i.remaining_ah; full += i.full_charge_ah;
    }
  }
  return { ready, needRecharge, degraded, capacityPct: full > 0 ? (rem / full) * 100 : 0 };
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
