/** CARTO basemap tiles and the runtime key they need.
 *
 *  CARTO answers keyless raster tile requests with "API KEY REQUIRED" placeholders. The
 *  repo and the server image are public, so the key is never built in: the server reads
 *  it from its environment and hands it out at runtime — /web/map-config to signed-in
 *  viewers (v2 Journey), /share/<token>/map-config to an active share link (guest page).
 *  Without it the map still renders, just with the placeholder tiles. */

export function tileUrl(theme: "dark" | "light", key?: string | null): string {
  const style = theme === "dark" ? "dark_all" : "light_all";
  const url = `https://{s}.basemaps.cartocdn.com/${style}/{z}/{x}/{y}{r}.png`;
  return key ? `${url}?key=${encodeURIComponent(key)}` : url;
}

/** `{"carto_key": "<key>" | null}` -> the key; null for a null key or any other shape. */
export function decodeCartoKey(body: unknown): string | null {
  if (typeof body !== "object" || body === null) return null;
  const key = (body as { carto_key?: unknown }).carto_key;
  return typeof key === "string" && key.length > 0 ? key : null;
}

/** GET a map-config endpoint -> the key, or null. Never throws: a missing key costs only
 *  the basemap imagery, so no failure here may break the page around the map. */
export async function fetchCartoKey(url: string): Promise<string | null> {
  try {
    const r = await fetch(url);
    if (!r.ok) return null;
    return decodeCartoKey(await r.json());
  } catch {
    return null;
  }
}

/** Fetch at most once: concurrent and later callers all share the first answer, failure
 *  (null) included — no polling, no retry. `current()` is that answer once it has settled
 *  (null before), readable synchronously so a remounted map starts with the key rather
 *  than flashing placeholder tiles. */
export function onceForSession(fetchKey: () => Promise<string | null>): {
  load: () => Promise<string | null>; current: () => string | null;
} {
  let pending: Promise<string | null> | null = null;
  let settled: string | null = null;
  return {
    load: () => {
      if (!pending) {
        pending = fetchKey().catch(() => null).then((key) => { settled = key; return key; });
      }
      return pending;
    },
    current: () => settled,
  };
}
