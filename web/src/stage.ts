import type { FleetItem } from "./types";

/**
 * WEB-9: pure main-stage selection (extracted from App.tsx so it's testable).
 *
 * Pinned packs (by address) win outright: if any pinned address is present in
 * the fleet, the stage is exactly those packs. A stale pin (address no longer
 * in the fleet) contributes nothing, so an all-stale pin set falls back to
 * auto selection.
 *
 * Auto: the stage is the WHOLE active base (group), not a single pack. The
 * lead pack is a fresh discharging pack, else the most-recently-updated fresh
 * pack, else the most-recent pack overall; the stage then expands to every
 * pack in the lead's group, alias-ordered, so it doesn't jump as packs poll
 * in and out.
 */
export function selectStageItems(
  items: FleetItem[],
  staleAddrs: Set<string>,
  pinned: Set<string>,
): FleetItem[] {
  const byAlias = (a: FleetItem, b: FleetItem) => (a.alias ?? "").localeCompare(b.alias ?? "");
  const pins = items.filter((i) => pinned.has(i.address));
  if (pins.length > 0) return pins.sort(byAlias);
  const fresh = items.filter((i) => !staleAddrs.has(i.address));
  const byRecent = (a: FleetItem, b: FleetItem) => b.ts_ms - a.ts_ms;
  const lead =
    fresh.find((i) => (i.current_a ?? 0) < -0.1) ??
    fresh.slice().sort(byRecent)[0] ??
    items.slice().sort(byRecent)[0];
  if (!lead) return [];
  const gid = lead.group_id;
  if (!gid) return [lead];
  return items.filter((i) => i.group_id === gid).sort(byAlias);
}
