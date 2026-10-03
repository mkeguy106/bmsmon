import type { FleetItem, Sample } from "./types";

export function createStore() {
  const fleet: Record<string, FleetItem> = {};
  const subs = new Set<() => void>();
  // Monotonic version, bumped on every change — a stable getSnapshot for
  // React's useSyncExternalStore (the fleet object itself is mutable).
  let version = 0;
  const notify = () => { version++; subs.forEach((f) => f()); };

  /** The alias/group meta a snapshot legitimately carries (never telemetry). */
  const metaOf = (meta: Partial<FleetItem>): Partial<FleetItem> => ({
    ...("alias" in meta ? { alias: meta.alias } : null),
    ...("group_id" in meta ? { group_id: meta.group_id } : null),
  });

  const merge = (s: Sample, meta?: Partial<FleetItem>) => {
    const cur = fleet[s.address];
    if (s.link_event != null) {
      // Link-event samples ("Connected"/"Disconnected") carry no telemetry — the server
      // rematerializes every omitted field as an explicit null, and spreading those would
      // wipe the pack's last-known soc/voltage/temp. They never refresh freshness either
      // (WEB-22): ts_ms stays the newest TELEMETRY time, so a pack whose link only flaps
      // still goes stale. The event is kept (link_event + link_ts_ms) only while it is newer
      // than that telemetry; freshness.ts reads a newer "Disconnected" as stale at once. A
      // link event at the SAME ms as the telemetry is kept too, whichever arrives first: the
      // tie goes to "gone", never to "live". A link event for a pack with no telemetry yet is
      // dropped: there is nothing to show. (Snapshot rows never carry a link event, so this
      // branch has no alias/group meta to merge.)
      if (!cur || cur.ts_ms > s.ts_ms || (cur.link_ts_ms ?? -Infinity) >= s.ts_ms) return false;
      fleet[s.address] = { ...cur, link_event: s.link_event, link_ts_ms: s.ts_ms };
      return true;
    }
    if (cur && cur.ts_ms >= s.ts_ms && !meta) return false;
    if (cur && cur.ts_ms > s.ts_ms && meta) {
      // Stale snapshot item — e.g. the REST fallback response landing after a
      // fresher WS sample already arrived. Never regress telemetry/ts; only
      // refresh the alias/group meta the snapshot legitimately carries.
      fleet[s.address] = { ...cur, ...metaOf(meta) };
      return true;
    }
    // Normal samples keep the full spread: explicit nulls are load-bearing
    // (e.g. eta_full_min: null must clear the ETA when charging stops). A link event that
    // is not older than this reading survives it, so a late-arriving older (or same-ms)
    // reading can't resurrect a pack the phone already reported gone; otherwise it is cleared.
    const keepLink = cur?.link_ts_ms != null && cur.link_ts_ms >= s.ts_ms;
    fleet[s.address] = {
      ...cur, ...meta, ...s,
      link_event: keepLink ? cur!.link_event : null,
      link_ts_ms: keepLink ? cur!.link_ts_ms : null,
    };
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
