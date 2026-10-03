import { useEffect, useState } from "react";
import { fetchCartoKey, onceForSession } from "./basemap";

/** One /web/map-config fetch chain per page session (transient failures retried with a
 *  capped backoff inside it), shared by every map mount. */
const webCartoKey = onceForSession(() => fetchCartoKey("/web/map-config"));

/** The CARTO basemap key for the v2 Journey map: `undefined` while the request is pending
 *  (JourneyMap holds its tiles for up to KEY_WAIT_MS), then the key, or null for good if
 *  the server has none or every attempt failed (the map then shows placeholder tiles). */
export function useCartoKey(): string | null | undefined {
  const [key, setKey] = useState<string | null | undefined>(webCartoKey.current);
  useEffect(() => {
    let alive = true;
    void webCartoKey.load().then((k) => { if (alive) setKey(k); });
    return () => { alive = false; };
  }, []);
  return key;
}
