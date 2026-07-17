// Liveness + current-position selectors for the Journey live map. Pure — the view feeds
// them from useFleetData's items (live WS-updated) and its resolved window.
// Design: docs/superpowers/specs/2026-07-13-journey-live-map-design.md
import type { FleetItem } from "../../types";
import type { TrackPoint } from "../track";

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

/** Freshest GPS fix among the base's packs regardless of age — the "last known" position
 *  shown (dimmed, with its age) when no fix is fresh enough for [livePosition]. */
export function lastKnownPosition(items: FleetItem[], addresses: string[]): LivePos | null {
  const addrs = new Set(addresses);
  let best: FleetItem | null = null;
  for (const it of items) {
    if (!addrs.has(it.address) || it.lat == null || it.lon == null) continue;
    if (!best || it.ts_ms > best.ts_ms) best = it;
  }
  return best ? { lat: best.lat!, lon: best.lon!, tsMs: best.ts_ms } : null;
}

/** The track's head = the last known GPS fix for the window. The track is built from
 *  GPS-carrying rows only, so unlike the fleet snapshot (whose latest row is usually a
 *  GPS-deduped, coordinate-less sample) it always yields a real position when any fix
 *  exists — this is what keeps the chair marker from vanishing between fixes. */
export function lastTrackPosition(points: TrackPoint[]): LivePos | null {
  if (points.length === 0) return null;
  const p = points[points.length - 1];
  return { lat: p.lat, lon: p.lon, tsMs: p.t };
}

/** Resolve the chair marker from all available fixes: show the NEWEST one, greyed when
 *  that fix is older than [LIVE_STALE_MS]. Only null when no fix exists at all — the
 *  marker must never vanish while any known position is available. Staleness is derived
 *  from the shown fix's real age, not from "the latest fleet row happened to lack GPS". */
export function resolveChairMarker(
  candidates: (LivePos | null)[], nowMs: number,
): { pos: LivePos | null; stale: boolean } {
  let best: LivePos | null = null;
  for (const c of candidates) if (c && (!best || c.tsMs > best.tsMs)) best = c;
  if (!best) return { pos: null, stale: false };
  return { pos: best, stale: nowMs - best.tsMs > LIVE_STALE_MS };
}
