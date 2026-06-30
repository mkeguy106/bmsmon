import type { FleetItem, Sample } from "./types";

export function createStore() {
  const fleet: Record<string, FleetItem> = {};
  const subs = new Set<() => void>();
  const notify = () => subs.forEach((f) => f());

  const merge = (s: Sample, meta?: Partial<FleetItem>) => {
    const cur = fleet[s.address];
    if (cur && cur.ts_ms >= s.ts_ms && !meta) return false;
    fleet[s.address] = { ...cur, ...meta, ...s };
    return true;
  };

  return {
    getFleet: () => fleet,
    applySnapshot(items: FleetItem[]) {
      items.forEach((i) => merge(i, i));
      notify();
    },
    applySample(s: Sample) {
      if (merge(s)) notify();
    },
    subscribe(fn: () => void) { subs.add(fn); return () => subs.delete(fn); },
  };
}
export type Store = ReturnType<typeof createStore>;
