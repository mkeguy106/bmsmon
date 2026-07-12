import type { FleetItem } from "../types";

export const DAILY_DRIVER_BASE = "2012";

export const isCharging = (i: FleetItem): boolean => (i.current_a ?? 0) > 0.1;
export const isDischarging = (i: FleetItem): boolean => (i.current_a ?? 0) < -0.1;

/** Cell spread in mV: from the real 4 cells when present, else cell_max_v − cell_min_v, else null. */
export function deltaMv(i: FleetItem): number | null {
  if (i.cells && i.cells.length > 0) return (Math.max(...i.cells) - Math.min(...i.cells)) * 1000;
  if (i.cell_min_v != null && i.cell_max_v != null) return (i.cell_max_v - i.cell_min_v) * 1000;
  return null;
}

export interface BasePack { item: FleetItem; letter: string; connected: boolean }
export type BaseStatus = "in-use" | "charging" | "backup" | "spares" | "offline";
export interface Base { id: string; label: string; packs: BasePack[]; status: BaseStatus }

const LETTERS = ["A", "B", "C", "D"];

function baseStatus(packs: BasePack[]): BaseStatus {
  const live = packs.filter((p) => p.connected);
  if (live.length === 0) return "offline";
  if (live.some((p) => isDischarging(p.item))) return "in-use";
  if (live.some((p) => isCharging(p.item))) return "charging";
  const anyFull = live.some((p) => (p.item.soc ?? 0) >= 99);
  return anyFull ? "backup" : "spares";
}

export function groupBases(items: FleetItem[], staleAddrs: Set<string>): Base[] {
  const byBase = new Map<string, FleetItem[]>();
  for (const i of items) {
    const gid = i.group_id ?? i.address;
    (byBase.get(gid) ?? byBase.set(gid, []).get(gid)!).push(i);
  }
  const bases: Base[] = [];
  for (const [id, group] of byBase) {
    const sorted = group.slice().sort((a, b) => (a.alias ?? "").localeCompare(b.alias ?? ""));
    const packs: BasePack[] = sorted.map((item, n) => ({
      item, letter: LETTERS[n] ?? String(n + 1), connected: !staleAddrs.has(item.address),
    }));
    bases.push({ id, label: `Base ${id}`, packs, status: baseStatus(packs) });
  }
  return bases.sort((a, b) =>
    a.id === DAILY_DRIVER_BASE ? -1 : b.id === DAILY_DRIVER_BASE ? 1 : a.id.localeCompare(b.id));
}
