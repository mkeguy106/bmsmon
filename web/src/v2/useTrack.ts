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
 * whenever the address set OR the window changes (keyed on
 * `addresses.join(",") + ":" + fromMs + ":" + toMs`). An `alive` guard drops a response
 * from a superseded request, and the last good track is kept on total failure so a
 * transient fetch error doesn't blank the chart.
 */
export function useTrack(
  addresses: string[],
  fromMs: number,
  toMs: number,
): TrackPoint[] {
  const [points, setPoints] = useState<TrackPoint[]>([]);
  const key = addresses.join(",") + ":" + fromMs + ":" + toMs;

  useEffect(() => {
    let alive = true;
    if (addresses.length === 0) {
      setPoints([]);
      return;
    }
    Promise.all(
      addresses.map((a) => getTrack(a, fromMs, toMs).catch(() => null)),
    ).then((results) => {
      if (!alive) return;
      const tracks = results.filter((t): t is Track => t != null);
      // Keep last on total failure: only replace when at least one address resolved,
      // so a transient error window doesn't blank the chart.
      if (tracks.length > 0) setPoints(mergeBaseTracks(tracks));
    });
    return () => { alive = false; };
    // key encodes addresses + window; the primitives it's built from are the deps.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return points;
}
