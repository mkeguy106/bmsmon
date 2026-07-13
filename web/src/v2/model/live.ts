// Liveness + current-position selectors for the Journey live map. Pure — the view feeds
// them from useFleetData's items (live WS-updated) and its resolved window.
// Design: docs/superpowers/specs/2026-07-13-journey-live-map-design.md
import type { FleetItem } from "../../types";

/** Trail re-poll cadence while live: one server track bucket. */
export const LIVE_REFRESH_MS = 15_000;

/** A fix older than this no longer says where the chair IS — hide the marker. */
export const LIVE_STALE_MS = 120_000;

export interface LivePos { lat: number; lon: number; tsMs: number }

/** Live = the selected window CONTAINS now (day = today, or a range spanning today). A
 *  fully-future window (e.g. tomorrow) is NOT live — there's nothing to poll yet. */
export function isWindowLive(fromMs: number, toMs: number, nowMs: number): boolean {
  return fromMs <= nowMs && nowMs < toMs;
}

/** Freshest GPS-carrying sample among the base's packs, or null when stale/absent. */
export function livePosition(
  items: FleetItem[], addresses: string[], nowMs: number,
): LivePos | null {
  const addrs = new Set(addresses);
  let best: FleetItem | null = null;
  for (const it of items) {
    if (!addrs.has(it.address) || it.lat == null || it.lon == null) continue;
    if (nowMs - it.ts_ms > LIVE_STALE_MS) continue;
    if (!best || it.ts_ms > best.ts_ms) best = it;
  }
  return best ? { lat: best.lat!, lon: best.lon!, tsMs: best.ts_ms } : null;
}
