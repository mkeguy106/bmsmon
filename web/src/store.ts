import type { FleetItem, Sample } from "./types";

export function createStore() {
  const fleet: Record<string, FleetItem> = {};
  const subs = new Set<() => void>();
  // Monotonic version, bumped on every change — a stable getSnapshot for
  // React's useSyncExternalStore (the fleet object itself is mutable).
  let version = 0;
  const notify = () => { version++; subs.forEach((f) => f()); };

  const merge = (s: Sample, meta?: Partial<FleetItem>) => {
    const cur = fleet[s.address];
    if (cur && cur.ts_ms >= s.ts_ms && !meta) return false;
    if (cur && cur.ts_ms > s.ts_ms && meta) {
      // Stale snapshot item — e.g. the REST fallback response landing after a
      // fresher WS sample already arrived. Never regress telemetry/ts; only
      // refresh the alias/group meta the snapshot legitimately carries.
      fleet[s.address] = {
        ...cur,
        ...("alias" in meta ? { alias: meta.alias } : null),
        ...("group_id" in meta ? { group_id: meta.group_id } : null),
      };
      return true;
    }
    if (cur && s.link_event != null) {
      // Link-event samples ("Connected"/"Disconnected") carry no telemetry —
      // the server rematerializes every omitted field as an explicit null, and
      // spreading those would wipe the pack's last-known soc/voltage/temp.
      // Merge only what a link event legitimately carries: the timestamp (so
      // staleness tracking works), the event itself, and any alias/group meta.
      fleet[s.address] = {
        ...cur,
        ...(meta && "alias" in meta ? { alias: meta.alias } : null),
        ...(meta && "group_id" in meta ? { group_id: meta.group_id } : null),
        ts_ms: s.ts_ms,
        link_event: s.link_event,
      };
      return true;
    }
    // Normal samples keep the full spread: explicit nulls are load-bearing
    // (e.g. eta_full_min: null must clear the ETA when charging stops).
    fleet[s.address] = { ...cur, ...meta, ...s };
    return true;
  };

  return {
    getFleet: () => fleet,
    getVersion: () => version,
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
