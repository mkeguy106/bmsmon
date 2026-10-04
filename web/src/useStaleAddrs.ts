import { useEffect, useState } from "react";
import { judgeStale, type StaleJudgement } from "./freshness";
import type { FleetItem } from "./types";

/** Staleness only needs coarse resolution against the 90 s threshold: it is re-judged on this
 *  cadence and whenever the items change, NOT every second. Components that render live age
 *  text subscribe to useNow(1000) themselves. */
const STALE_TICK_MS = 5_000;

/**
 * The addresses of the stale packs in [items], judged in the SAME render as the items. It
 * used to be filled in an effect, after paint: every pack rendered live for a frame on load,
 * and each snapshot's newly stale packs rendered live for a frame (stage rings, rail, SYNCED,
 * alert counts, stage selection). A new items list is judged during render instead, so React
 * never commits items with an older judgement.
 *
 * Identity-stable: the Set only changes when its membership does, and the coarse tick's state
 * update bails out when nothing changed, so nothing downstream re-renders on the tick.
 */
export function useStaleAddrs(items: readonly FleetItem[]): Set<string> {
  const [judged, setJudged] = useState<StaleJudgement>(() => judgeStale(null, items, Date.now()));
  let current = judged;
  if (judged.items !== items) {
    // A render-time update for derived state: React re-renders at once with it, before
    // committing, so no child ever sees these items with the previous judgement.
    current = judgeStale(judged, items, Date.now());
    setJudged(current);
  }
  useEffect(() => {
    const t = setInterval(() => setJudged((prev) => judgeStale(prev, prev.items, Date.now())), STALE_TICK_MS);
    return () => clearInterval(t);
  }, []);
  return current.stale;
}
