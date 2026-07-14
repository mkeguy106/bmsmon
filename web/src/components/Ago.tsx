import { useNow } from "../useNow";
import { relAgo } from "../util";

/**
 * Self-ticking "5m ago" text leaf. Owns its own 1 s clock via useNow, so a
 * live age label costs one text-node re-render per second HERE instead of the
 * parent (or the whole app) carrying a ticking `now` prop.
 */
export function Ago({ tsMs }: { tsMs: number }) {
  return <>{relAgo(tsMs, useNow(1000))}</>;
}
