import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { STALE_MS } from "./freshness";
import type { FleetItem } from "./types";
import { useStaleAddrs } from "./useStaleAddrs";

function Probe({ items }: { items: FleetItem[] }) {
  const stale = useStaleAddrs(items);
  return <>{items.map((i) => `${i.address}:${stale.has(i.address) ? "stale" : "live"}`).join(" ")}</>;
}

// The first paint is the one a server render shows: effects have not run. Staleness used to be
// filled by an effect, so this paint read every pack as live.
describe("useStaleAddrs", () => {
  it("judges freshness before the first paint: a stale pack never paints live", () => {
    const now = Date.now();
    const items: FleetItem[] = [
      { address: "A", ts_ms: now, soc: 60 },
      { address: "B", ts_ms: now - STALE_MS - 60_000, soc: 40 },
      { address: "C", ts_ms: now, soc: 50, link_event: "Disconnected", link_ts_ms: now },
    ];
    expect(renderToStaticMarkup(<Probe items={items} />)).toBe("A:live B:stale C:stale");
  });
});
