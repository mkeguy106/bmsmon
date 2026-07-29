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

export const PREDICT_MAX_MS = 10_000;
export const PREDICT_MAX_M = 200;

const M_PER_DEG_LAT = 111_320;

/**
 * Dead-reckon the chair between fixes so the marker glides instead of teleporting every
 * ~9-15 s. Velocity comes from the last two points of the CLEANED track (already smoothed,
 * so this is the filter's velocity without plumbing filter state through cleanTrack).
 *
 * Extrapolation is capped at PREDICT_MAX_MS or PREDICT_MAX_M, whichever binds first — at
 * train speed the distance cap binds, walking the time cap does — so the marker never claims
 * a position the model cannot support. Past the cap it holds the last position, where the
 * existing LIVE_STALE_MS greying takes over. Never extrapolates across an `inferred` segment:
 * that velocity was inferred, not measured.
 */
export function predictPosition(points: TrackPoint[], nowMs: number): LivePos | null {
  if (points.length === 0) return null;
  const last = points[points.length - 1];
  const held: LivePos = { lat: last.lat, lon: last.lon, tsMs: last.t };
  if (points.length < 2 || last.inferred) return held;

  const prev = points[points.length - 2];
  const dtS = (last.t - prev.t) / 1000;
  if (dtS <= 0) return held;

  const aheadMs = Math.min(Math.max(0, nowMs - last.t), PREDICT_MAX_MS);
  if (aheadMs === 0) return held;

  const mPerDegLon = M_PER_DEG_LAT * Math.max(Math.abs(Math.cos((last.lat * Math.PI) / 180)), 1e-6);
  const vE = ((last.lon - prev.lon) * mPerDegLon) / dtS;
  const vN = ((last.lat - prev.lat) * M_PER_DEG_LAT) / dtS;
  const speed = Math.hypot(vE, vN);
  if (speed === 0) return held;

  const aheadS = Math.min(aheadMs / 1000, PREDICT_MAX_M / speed);
  return {
    lat: last.lat + (vN * aheadS) / M_PER_DEG_LAT,
    lon: last.lon + (vE * aheadS) / mPerDegLon,
    tsMs: last.t,
  };
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
