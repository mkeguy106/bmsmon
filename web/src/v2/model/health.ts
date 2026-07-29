import type { FleetItem } from "../../types";
import { isCharging, isDischarging } from "../fleet";

export const READY_SOC = 90;
export const RECHARGE_SOC = 30;
export const DEGRADED_SOH = 80;

export interface HealthSummary {
  ready: number; needRecharge: number; degraded: number;
  capacityPct: number;
  /** Offline packs whose LAST-KNOWN capacity is folded into capacityPct. */
  staleCounted: number;
}

export function healthSummary(items: FleetItem[], staleAddrs: Set<string>): HealthSummary {
  const live = items.filter((i) => !staleAddrs.has(i.address));
  // READY / NEED RECHARGE are actionable-now counts, so they stay live-only:
  // an out-of-range pack is not a pack you can go grab and rely on.
  const ready = live.filter((i) => (i.soc ?? -1) >= READY_SOC).length;
  const needRecharge = live.filter((i) => (i.soc ?? Infinity) < RECHARGE_SOC).length;
  const degraded = items.filter((i) => i.soh != null && i.soh < DEGRADED_SOH).length;
  // FLEET CAPACITY is stored energy, not connectivity: a pack out of BLE range
  // still holds its charge, so offline packs contribute their LAST-KNOWN Ah
  // (counted in staleCounted so the tile can say so). Excluding them made the
  // fleet read empty whenever the phone was away from the spares.
  let rem = 0, full = 0, staleCounted = 0;
  for (const i of items) {
    if (i.remaining_ah != null && i.full_charge_ah != null && i.full_charge_ah > 0) {
      rem += i.remaining_ah; full += i.full_charge_ah;
      if (staleAddrs.has(i.address)) staleCounted++;
    }
  }
  return { ready, needRecharge, degraded, capacityPct: full > 0 ? (rem / full) * 100 : 0, staleCounted };
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
