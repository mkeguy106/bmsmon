// Incremental track merging for useTrack's live refresh. On interval ticks the hook
// refetches only [seamT, toMs) — FROM the last bucket's start, not after it, because the
// newest 15 s server bucket is partially filled and its averages keep changing until the
// bucket closes. This pure function splices that incremental response onto the previous
// track, and — critically for render cost — returns the PREVIOUS array identity when the
// response contains nothing new, so every downstream memo (cleanTrack, cumulativeMiles,
// the Leaflet trail-rebuild effect, …) no-ops.
import type { TrackPoint } from "../track";

const eqPoint = (a: TrackPoint, b: TrackPoint): boolean =>
  a.t === b.t && a.lat === b.lat && a.lon === b.lon &&
  a.power_w === b.power_w && a.current_a === b.current_a && a.soc === b.soc;

/**
 * Merge an incremental refetch into the previous track.
 *
 * - `incoming` was fetched over [seamT, toMs): drop prev's points at/after the cut and
 *   append `incoming` (the seam bucket is REPLACED — its averages may have changed).
 * - If `incoming` starts before `seamT` (server returned more overlap than asked), the
 *   cut moves back to the incoming's first t so no duplicate/out-of-order buckets survive.
 * - If the spliced result is element-wise identical to `prev`, returns `prev` by identity.
 * - An EMPTY `incoming` returns `prev` unchanged: a valid-but-empty response is "no news",
 *   never a reason to truncate the seam bucket off a live trail.
 * - `seamT = -Infinity` degrades to a full replace with the same identity-preserving
 *   no-change check — used by the hook's periodic full-refetch safety net.
 */
export function appendTrack(prev: TrackPoint[], incoming: TrackPoint[], seamT: number): TrackPoint[] {
  if (incoming.length === 0) return prev;
  const cutT = Math.min(seamT, incoming[0].t);
  let keep = prev.length;
  while (keep > 0 && prev[keep - 1].t >= cutT) keep--;
  if (prev.length - keep === incoming.length) {
    let same = true;
    for (let i = 0; i < incoming.length; i++) {
      if (!eqPoint(prev[keep + i], incoming[i])) { same = false; break; }
    }
    if (same) return prev;
  }
  return prev.slice(0, keep).concat(incoming);
}
