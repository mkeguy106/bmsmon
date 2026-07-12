import { useEffect, useState } from "react";
import { getTrends } from "../api";
import type { TrendSeries } from "./trends";

/**
 * Fetch trend series for one or more pack addresses over [fromMs, toMs].
 *
 * Fetches every address in parallel and returns a Map keyed by address. Refetch
 * fires whenever the address set OR the window changes (the effect keys on
 * `addresses.join(",") + ":" + fromMs + ":" + toMs`). An `alive` guard drops a
 * response from a superseded request, and the last good Map is kept on error so
 * a transient fetch failure doesn't blank the charts.
 */
export function useTrends(
  addresses: string[],
  fromMs: number,
  toMs: number,
): Map<string, TrendSeries> {
  const [series, setSeries] = useState<Map<string, TrendSeries>>(new Map());
  const key = addresses.join(",") + ":" + fromMs + ":" + toMs;

  useEffect(() => {
    let alive = true;
    if (addresses.length === 0) {
      setSeries(new Map());
      return;
    }
    Promise.all(
      addresses.map((a) =>
        getTrends(a, fromMs, toMs)
          .then((s) => [a, s] as const)
          .catch(() => null),
      ),
    ).then((results) => {
      if (!alive) return;
      const next = new Map<string, TrendSeries>();
      for (const r of results) if (r) next.set(r[0], r[1]);
      // Keep last on total failure: only replace when at least one address
      // resolved, so a transient error window doesn't blank the charts.
      if (next.size > 0) setSeries(next);
    });
    return () => { alive = false; };
    // key encodes addresses + window; the primitives it's built from are the deps.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return series;
}
