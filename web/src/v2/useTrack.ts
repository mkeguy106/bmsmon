import { useEffect, useState } from "react";
import { getTrack } from "../api";
import type { Track, TrackPoint } from "./track";
import { mergeBaseTracks } from "./model/journey";

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
 * window/address change always clears first. When `refreshMs` is set, the same window is
 * refetched on that interval (live mode); cleanup clears the timer.
 */
export function useTrack(
  addresses: string[],
  fromMs: number,
  toMs: number,
  refreshMs?: number,
): TrackPoint[] {
  const [points, setPoints] = useState<TrackPoint[]>([]);
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
    const load = () => {
      Promise.all(
        addresses.map((a) => getTrack(a, fromMs, toMs).catch(() => null)),
      ).then((results) => {
        if (!alive) return;
        const tracks = results.filter((t): t is Track => t != null);
        // Keep last on total failure: only replace when at least one address resolved,
        // so a transient error window doesn't blank the chart.
        if (tracks.length > 0) setPoints(mergeBaseTracks(tracks));
      });
    };
    load();
    // Live windows re-poll on an interval (one server bucket); undefined = fetch once.
    const timer = refreshMs != null ? setInterval(load, refreshMs) : null;
    return () => {
      alive = false;
      if (timer != null) clearInterval(timer);
    };
    // key encodes addresses + window + refresh; the primitives it's built from are the deps.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return points;
}
