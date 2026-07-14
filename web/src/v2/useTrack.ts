import { useEffect, useRef, useState } from "react";
import { getTrack } from "../api";
import type { Track, TrackPoint } from "./track";
import { mergeBaseTracks } from "./model/journey";
import { appendTrack } from "./model/appendTrack";
import { visibleInterval } from "../visiblePoll";

// Live refreshes go incremental (see below), but a full-window refetch still runs on
// this cadence as a safety net — it heals anything an increment can't see (server-side
// deletions, a bucket that changed BEHIND the seam, drift after long clock skew).
const FULL_REFRESH_MS = 10 * 60_000;

/**
 * Fetch + merge every pack's track for a base over [fromMs, toMs] into one chair track.
 *
 * Fetches `getTrack` for each address in parallel, with a per-address `.catch(() => null)`
 * so one failing pack doesn't sink the whole trip. Surviving tracks are combined with
 * `mergeBaseTracks` (align by t, sum power/current, mean coords, min soc). Refetch fires
 * whenever the address set, window, or `refreshMs` changes (keyed on
 * `addresses.join(",") + ":" + fromMs + ":" + toMs + ":" + refreshMs`). Each such key change
 * also clears `points` to `[]` immediately, before the new fetch resolves, so consumers never
 * draw/fit the previous window's track against the new one. An `alive` guard drops a response
 * from a superseded request, and the last good track is kept on total failure — but only
 * *within* a window: interval refetch failures (same key) keep the last good track, while a
 * window/address change always clears first.
 *
 * Live refresh is INCREMENTAL: on interval ticks (visibility-gated — hidden tabs don't
 * poll, and a returning tab catches up immediately) only [lastBucketT, toMs) is fetched
 * per address — from the last bucket's START, because the newest 15 s bucket is partially
 * filled and its averages keep changing; the seam bucket is replaced, new buckets append
 * (`appendTrack`). When nothing changed, the PREVIOUS array identity is returned so every
 * downstream memo and map effect no-ops. Full refetches still run on mount, on any
 * window/address change, whenever the track is still empty, if any address's incremental
 * fetch fails (all-or-nothing: a partial increment would mis-sum the merged buckets), and
 * every FULL_REFRESH_MS as a safety net.
 */
export function useTrack(
  addresses: string[],
  fromMs: number,
  toMs: number,
  refreshMs?: number,
): TrackPoint[] {
  const [points, setPoints] = useState<TrackPoint[]>([]);
  // The refresh tick needs the CURRENT track (for the seam bucket) without re-running
  // the effect on every data change — mirror state into a ref.
  const pointsRef = useRef<TrackPoint[]>(points);
  useEffect(() => { pointsRef.current = points; }, [points]);
  const key = addresses.join(",") + ":" + fromMs + ":" + toMs + ":" + (refreshMs ?? 0);

  useEffect(() => {
    let alive = true;
    if (addresses.length === 0) {
      setPoints([]);
      return;
    }
    // Window/addresses changed: drop the previous window's track immediately so consumers
    // never fit/draw the old day against the new window. (The refresh interval reuses this
    // effect only via key changes, so live re-polls do NOT pass here.)
    setPoints([]);
    pointsRef.current = [];
    let lastFullMs = 0;

    const fullLoad = () => {
      lastFullMs = Date.now();
      Promise.all(
        addresses.map((a) => getTrack(a, fromMs, toMs).catch(() => null)),
      ).then((results) => {
        if (!alive) return;
        const tracks = results.filter((t): t is Track => t != null);
        // Keep last on total failure: only replace when at least one address resolved,
        // so a transient error window doesn't blank the chart. appendTrack with a
        // -Infinity seam = full replace that keeps the previous identity when unchanged.
        if (tracks.length > 0) {
          const merged = mergeBaseTracks(tracks);
          setPoints((prev) => appendTrack(prev, merged, Number.NEGATIVE_INFINITY));
        }
      });
    };

    const refresh = () => {
      const prev = pointsRef.current;
      if (prev.length === 0 || Date.now() - lastFullMs >= FULL_REFRESH_MS) {
        fullLoad();
        return;
      }
      const seamT = prev[prev.length - 1].t;
      Promise.all(
        addresses.map((a) => getTrack(a, seamT, toMs).catch(() => null)),
      ).then((results) => {
        if (!alive) return;
        // All-or-nothing: mergeBaseTracks sums power/current across packs per bucket, so
        // an increment missing one pack would splice mis-summed seam buckets into the
        // track. Keep the last good track; the next tick (or the safety net) retries.
        if (results.some((r) => r == null)) return;
        const inc = mergeBaseTracks(results as Track[]);
        // Apply only if the state the seam was computed against is still current — a
        // racing full reload wins over a stale increment.
        setPoints((cur) => (cur === prev ? appendTrack(prev, inc, seamT) : cur));
      });
    };

    fullLoad();
    // Live windows re-poll on an interval (one server bucket); undefined = fetch once.
    // visibleInterval skips ticks while the tab is hidden and catches up on refocus.
    const stopTimer = refreshMs != null ? visibleInterval(refresh, refreshMs) : null;
    return () => {
      alive = false;
      stopTimer?.();
    };
    // key encodes addresses + window + refresh; the primitives it's built from are the deps.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return points;
}
