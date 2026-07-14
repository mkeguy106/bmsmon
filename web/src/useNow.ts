import { useEffect, useState } from "react";

/**
 * Epoch-ms "now" that re-renders the calling component every [intervalMs].
 * Subscribe in the LEAF component that actually renders time-derived text
 * (age labels, clock times) — never in a shared data hook — so the tick's
 * re-render cost stays confined to that leaf instead of the whole app.
 */
export function useNow(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(t);
  }, [intervalMs]);
  return now;
}
